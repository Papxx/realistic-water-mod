// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.client.render;

import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.physics.PbfSolver;
import java.util.Arrays;

/**
 * Turns the particles into camera-relative micro-cubes: render interpolation, culling, back-to-front sorting, the look
 * of each material and the box geometry. Plain Java without any rendering API, so it can be unit tested;
 * {@link ParticleBatchRenderer} feeds its output into a vertex buffer.
 *
 * <p>Each frame is meshed in two calls. {@link #prepare} interpolates every particle between its last two tick
 * positions, drops particles behind the camera and sorts the rest far to near. {@link #emit} then writes up to three
 * quads per particle: only the faces that point towards the camera, wound counter-clockwise as seen from it.
 *
 * <p>Sorting per particle (instead of the per-quad sort vanilla runs for translucent buffers) is what lets water and
 * slime be translucent: a cube is convex, so drawing whole cubes far to near and only their front faces blends
 * correctly.
 *
 * <p>Fluids are stretched along their velocity, up to {@link #MAX_STRETCH} times their length, and narrowed so their
 * volume stays the same (Plan.md §7.4). The box is the unit cube transformed by the symmetric matrix
 * {@code M = w I + (s - w) u u^T} ({@code u} the direction of motion, {@code s} the stretch, {@code w = 1 / sqrt(s)}),
 * a parallelepiped that turns into the axis-aligned cube as the particle slows down, so resting particles do not
 * flicker between orientations.
 *
 * <p>Allocation-free once the scratch arrays have grown to the simulation's capacity. Not thread-safe: render thread
 * only.
 */
public final class ParticleMesher {
    /** Receives the quads of {@link #emit}: four vertices each, positions relative to the camera. */
    public interface VertexSink {
        /**
         * @param argb        colour with alpha, face shading already applied
         * @param packedLight block and sky light packed like vanilla's lightmap coordinates
         */
        void vertex(float x, float y, float z, int argb, int packedLight);
    }

    /** World data a particle's look depends on. Called once per visible particle, so implementations should cache. */
    public interface BlockEnvironment {
        /** Packed block and sky light of a world block, as vanilla's {@code LightTexture.pack}. */
        int packedLight(int blockX, int blockY, int blockZ);

        /** Biome water colour at a world block, as {@code 0xRRGGBB}. */
        int waterColor(int blockX, int blockY, int blockZ);
    }

    /** Lightmap coordinates of full block and sky light, as vanilla's {@code LightTexture.FULL_BRIGHT}. */
    public static final int FULL_BRIGHT = 0xF000F0;

    /** Edge length of a particle cube (Plan.md §5 Step 4), a little smaller than its 0.25-block share of a block. */
    public static final float CUBE_SIZE = 0.22f;
    /** Edge length of an isolated water droplet (Plan.md §7.4). */
    public static final float SPRAY_SIZE = 0.16f;
    /** Longest a fluid particle is stretched along its velocity, relative to its resting length. */
    public static final float MAX_STRETCH = 1.8f;
    /** Fluids slower than this (blocks per second) stay cubes. */
    static final float STRETCH_START_SPEED = 0.5f;
    /** Stretch gained per block per second above {@link #STRETCH_START_SPEED}; the maximum is reached near 6 blocks/s. */
    static final float STRETCH_PER_SPEED = 0.15f;
    /**
     * Particles whose centre lies this far behind the camera plane are skipped. Covers the largest box (a fully
     * stretched cube reaches about 0.23 blocks from its centre) plus the near plane.
     */
    static final float CULL_MARGIN = 0.3f;
    /** Sort keys resolve distance in steps of 1/32 block, up to 2048 blocks. */
    private static final float SORT_STEPS_PER_BLOCK = 32f;
    private static final int SORT_KEY_MAX = 0xFFFF;

    /** Water particles with fewer neighbours than this count as spray (Plan.md §7.4). */
    static final int SPRAY_NEIGHBORS = 3;
    /**
     * Isolated water turns white only while it flies: from the first speed (blocks per second) on, fully at the
     * second. A thin film resting on the ground is isolated too, and stays water-coloured.
     */
    static final float SPRAY_MOVING_START = 0.5f;
    static final float SPRAY_MOVING_FULL = 2f;
    /**
     * Turbulence (blocks per second) where foam starts and where it is fully white. Turbulence is how much a
     * particle's velocity changed within the last tick: about 0.25 in free fall, several blocks per second where water
     * hits the ground or splashes off something.
     */
    static final float TURBULENCE_START = 2f;
    static final float TURBULENCE_FULL = 6f;
    /**
     * Fast water only lightens towards foam, by at most this much (from the first speed on, fully at the second): free
     * fall reaches the speed limit within a second, and a falling bucket of water is not all foam.
     */
    static final float SPEED_FOAM_MAX = 0.35f;
    static final float SPEED_FOAM_START = 5f;
    static final float SPEED_FOAM_FULL = PbfSolver.MAX_SPEED;
    private static final float TICKS_PER_SECOND = 1f / PbfSolver.TICK_SECONDS;
    private static final float WATER_ALPHA = 0.62f;
    private static final float FOAM_ALPHA = 0.85f;
    /** Foam and spray colour, {@code #E8F7FF}. */
    private static final float FOAM_R = 0xE8 / 255f;
    private static final float FOAM_G = 0xF7 / 255f;
    private static final float FOAM_B = 1f;
    /** Thin particles show less depth than a vanilla water block, so the biome colour is lightened by this much. */
    private static final float WATER_LIGHTEN = 0.15f;

    /** Resting lava starts to crust over after this many ticks and is fully crusted after the second. */
    static final int LAVA_CRUST_START_TICKS = 20;
    static final int LAVA_CRUST_FULL_TICKS = 160;
    /** Flicker cycles per tick (one per second). */
    private static final float LAVA_FLICKER_RATE = 0.05f;

    private static final int[] NEXT_AXIS = {1, 2, 0};
    private static final int[] PREV_AXIS = {2, 0, 1};

    // Camera-relative interpolated centres, indexed by particle.
    private float[] rx = new float[0];
    private float[] ry = new float[0];
    private float[] rz = new float[0];
    // Visible particles and their sort keys; the tmp arrays are the radix sort's second buffer.
    private int[] order = new int[0];
    private int[] orderTmp = new int[0];
    private int[] keys = new int[0];
    private int[] keysTmp = new int[0];
    private final int[] radixCount = new int[256];
    private int visible;
    private double camX;
    private double camY;
    private double camZ;

    // Box of the particle being emitted: half-axis vectors and face normals (unnormalised), indexed by axis.
    private final float[] axisX = new float[3];
    private final float[] axisY = new float[3];
    private final float[] axisZ = new float[3];
    private final float[] normalX = new float[3];
    private final float[] normalY = new float[3];
    private final float[] normalZ = new float[3];

    /**
     * Interpolates, culls and sorts the particles for one frame.
     *
     * @param partialTick fraction of the current tick that has passed, 0..1
     * @param camX        camera position in world coordinates
     * @param fwdX        unit vector the camera looks along
     * @return the number of particles {@link #emit} will draw
     */
    public int prepare(ParticleSoA p, float partialTick, double camX, double camY, double camZ,
                       float fwdX, float fwdY, float fwdZ) {
        int n = p.activeCount;
        ensureCapacity(n);
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
        // The camera relative to the particles' origin; positions are floats relative to it (see ParticleSoA).
        float cx = (float) (camX - p.originX);
        float cy = (float) (camY - p.originY);
        float cz = (float) (camZ - p.originZ);
        float t = partialTick;
        int count = 0;
        for (int i = 0; i < n; i++) {
            float x = p.prevX[i] + (p.posX[i] - p.prevX[i]) * t - cx;
            float y = p.prevY[i] + (p.posY[i] - p.prevY[i]) * t - cy;
            float z = p.prevZ[i] + (p.posZ[i] - p.prevZ[i]) * t - cz;
            rx[i] = x;
            ry[i] = y;
            rz[i] = z;
            if (x * fwdX + y * fwdY + z * fwdZ < -CULL_MARGIN) {
                continue;
            }
            int q = (int) ((float) Math.sqrt(x * x + y * y + z * z) * SORT_STEPS_PER_BLOCK);
            // Ascending keys put the farthest particle first.
            keys[count] = SORT_KEY_MAX - Math.min(q, SORT_KEY_MAX);
            order[count] = i;
            count++;
        }
        radixPass(keys, order, keysTmp, orderTmp, count, 0);
        radixPass(keysTmp, orderTmp, keys, order, count, 8);
        visible = count;
        return count;
    }

    /** Particle index of the {@code s}-th particle to draw (far to near), after {@link #prepare}. */
    int drawOrder(int s) {
        return order[s];
    }

    /**
     * Writes the quads of the particles found by the last {@link #prepare}, far to near. Up to three quads per
     * particle: the faces pointing towards the camera.
     *
     * @param timeTicks a clock in ticks for animated looks (lava flicker); only its fractional changes matter
     */
    public void emit(ParticleSoA p, BlockEnvironment env, VertexSink sink, float timeTicks) {
        for (int s = 0; s < visible; s++) {
            int i = order[s];
            float x = rx[i];
            float y = ry[i];
            float z = rz[i];
            float vx = p.velX[i];
            float vy = p.velY[i];
            float vz = p.velZ[i];
            float speed = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
            int blockX = (int) Math.floor(camX + x);
            int blockY = (int) Math.floor(camY + y);
            int blockZ = (int) Math.floor(camZ + z);
            float half = CUBE_SIZE * 0.5f;
            boolean stretch = true;
            boolean emissive = false;
            int argb;
            int light;
            switch (p.material[i]) {
                case ParticleSoA.MATERIAL_WATER -> {
                    float spray = sprayAmount(p.neighbors[i]);
                    // Velocity now minus the mean velocity of the last tick: what the tick changed.
                    float tx = vx - (p.posX[i] - p.prevX[i]) * TICKS_PER_SECOND;
                    float ty = vy - (p.posY[i] - p.prevY[i]) * TICKS_PER_SECOND;
                    float tz = vz - (p.posZ[i] - p.prevZ[i]) * TICKS_PER_SECOND;
                    float turbulence = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
                    argb = waterColor(env.waterColor(blockX, blockY, blockZ), waterFoam(speed, spray, turbulence));
                    half = lerp(CUBE_SIZE, SPRAY_SIZE, spray) * 0.5f;
                    light = env.packedLight(blockX, blockY, blockZ);
                }
                case ParticleSoA.MATERIAL_LAVA -> {
                    argb = lavaColor(speed, p.sleepTicks[i], mix(i), timeTicks);
                    light = FULL_BRIGHT;
                    emissive = true;
                }
                case ParticleSoA.MATERIAL_SLIME -> {
                    argb = slimeColor(speed);
                    light = env.packedLight(blockX, blockY, blockZ);
                }
                case ParticleSoA.MATERIAL_SAND -> {
                    argb = sandColor(mix(i));
                    light = env.packedLight(blockX, blockY, blockZ);
                    stretch = false;
                }
                default -> {
                    continue;
                }
            }
            setBox(half, vx, vy, vz, stretch ? speed : 0f);
            emitBox(sink, x, y, z, argb, light, emissive);
        }
    }

    /**
     * Sets the half-axes and face normals of a box with half edge length {@code half}, stretched along the velocity
     * when {@code speed} is above {@link #STRETCH_START_SPEED} (pass 0 for a plain cube).
     */
    private void setBox(float half, float vx, float vy, float vz, float speed) {
        float s = stretchFactor(speed);
        float ux = 0f;
        float uy = 0f;
        float uz = 0f;
        float w = 1f;
        if (s > 1f) {
            w = (float) (1.0 / Math.sqrt(s));
            float inv = 1f / speed;
            ux = vx * inv;
            uy = vy * inv;
            uz = vz * inv;
        }
        // Half-axis k is half * M e_k = half * (w e_k + (s - w) u u_k).
        float a = (s - w) * half;
        float b = w * half;
        // Face normal k is M^-T e_k = M^-1 e_k = e_k / w + (1/s - 1/w) u u_k; scaled by w (only the direction matters).
        float c = w / s - 1f;
        for (int k = 0; k < 3; k++) {
            float uk = k == 0 ? ux : k == 1 ? uy : uz;
            axisX[k] = a * ux * uk + (k == 0 ? b : 0f);
            axisY[k] = a * uy * uk + (k == 1 ? b : 0f);
            axisZ[k] = a * uz * uk + (k == 2 ? b : 0f);
            normalX[k] = c * ux * uk + (k == 0 ? 1f : 0f);
            normalY[k] = c * uy * uk + (k == 1 ? 1f : 0f);
            normalZ[k] = c * uz * uk + (k == 2 ? 1f : 0f);
        }
    }

    /** Stretch along the velocity for a fluid moving at {@code speed} blocks per second: 1 (a cube) up to the maximum. */
    static float stretchFactor(float speed) {
        if (!(speed > STRETCH_START_SPEED)) {
            return 1f;
        }
        return 1f + Math.min((speed - STRETCH_START_SPEED) * STRETCH_PER_SPEED, MAX_STRETCH - 1f);
    }

    /** Emits the faces of the box set by {@link #setBox} centred at camera-relative ({@code cx}, {@code cy}, {@code cz}). */
    private void emitBox(VertexSink sink, float cx, float cy, float cz, int argb, int light, boolean emissive) {
        for (int k = 0; k < 3; k++) {
            float nx = normalX[k];
            float ny = normalY[k];
            float nz = normalZ[k];
            // The camera sits at the origin. Face +k (plane through c + a_k, outward normal n) faces it when
            // n . (c + a_k) < 0; face -k (through c - a_k, normal -n) when n . (c - a_k) > 0. n . a_k is positive.
            float d = nx * cx + ny * cy + nz * cz;
            float e = nx * axisX[k] + ny * axisY[k] + nz * axisZ[k];
            float sign;
            if (d + e < 0f) {
                sign = 1f;
            } else if (d - e > 0f) {
                sign = -1f;
            } else {
                continue;
            }
            float invLen = sign / (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            float shade = faceShade(nx * invLen, ny * invLen, nz * invLen);
            if (emissive) {
                // Glowing lava keeps most of its brightness on every side; a little shading still shows its shape.
                shade = 0.6f + 0.4f * shade;
            }
            int color = shadeColor(argb, shade);
            int i = NEXT_AXIS[k];
            int j = PREV_AXIS[k];
            float fx = cx + sign * axisX[k];
            float fy = cy + sign * axisY[k];
            float fz = cz + sign * axisZ[k];
            float ix = axisX[i];
            float iy = axisY[i];
            float iz = axisZ[i];
            float jx = axisX[j];
            float jy = axisY[j];
            float jz = axisZ[j];
            // (k, i, j) is a right-handed cycle, so (-i,-j) (+i,-j) (+i,+j) (-i,+j) runs counter-clockwise seen from
            // outside face +k; face -k uses the reverse order.
            sink.vertex(fx - ix - jx, fy - iy - jy, fz - iz - jz, color, light);
            if (sign > 0f) {
                sink.vertex(fx + ix - jx, fy + iy - jy, fz + iz - jz, color, light);
                sink.vertex(fx + ix + jx, fy + iy + jy, fz + iz + jz, color, light);
                sink.vertex(fx - ix + jx, fy - iy + jy, fz - iz + jz, color, light);
            } else {
                sink.vertex(fx - ix + jx, fy - iy + jy, fz - iz + jz, color, light);
                sink.vertex(fx + ix + jx, fy + iy + jy, fz + iz + jz, color, light);
                sink.vertex(fx + ix - jx, fy + iy - jy, fz + iz - jz, color, light);
            }
        }
    }

    /**
     * Brightness of a face with outward unit normal ({@code nx}, {@code ny}, {@code nz}), matching vanilla's block
     * shading on axis-aligned faces (top 1.0, north/south 0.8, east/west 0.6, bottom 0.5) and blending in between.
     */
    static float faceShade(float nx, float ny, float nz) {
        return 0.6f * nx * nx + 0.8f * nz * nz + (ny > 0f ? 1f : 0.5f) * ny * ny;
    }

    /** How much of a water particle is spray, from its neighbour count: 1 alone, 0 from {@link #SPRAY_NEIGHBORS} on. */
    static float sprayAmount(byte neighbors) {
        if (neighbors < 0 || neighbors >= SPRAY_NEIGHBORS) {
            return 0f; // not stepped yet, or inside the fluid
        }
        return (SPRAY_NEIGHBORS - neighbors) / (float) SPRAY_NEIGHBORS;
    }

    /**
     * How white a water particle is, 0..1: flying spray and turbulent water (impacts, splashes) foam fully, fast but
     * smooth water (free fall, a fast stream) lightens a little.
     *
     * @param spray      0..1, see {@link #sprayAmount}
     * @param turbulence velocity change within the last tick, in blocks per second
     */
    static float waterFoam(float speed, float spray, float turbulence) {
        float flyingSpray = spray * smoothstep(SPRAY_MOVING_START, SPRAY_MOVING_FULL, speed);
        float fast = SPEED_FOAM_MAX * smoothstep(SPEED_FOAM_START, SPEED_FOAM_FULL, speed);
        return Math.max(Math.max(flyingSpray, fast), smoothstep(TURBULENCE_START, TURBULENCE_FULL, turbulence));
    }

    /**
     * Water: the biome colour, lightened a little, blended towards white foam.
     *
     * @param tintRgb biome water colour, {@code 0xRRGGBB}
     * @param foam    0..1, see {@link #waterFoam}
     */
    static int waterColor(int tintRgb, float foam) {
        float r = lighten(((tintRgb >> 16) & 0xFF) / 255f);
        float g = lighten(((tintRgb >> 8) & 0xFF) / 255f);
        float b = lighten((tintRgb & 0xFF) / 255f);
        return argb(lerp(WATER_ALPHA, FOAM_ALPHA, foam), lerp(r, FOAM_R, foam), lerp(g, FOAM_G, foam),
                lerp(b, FOAM_B, foam));
    }

    private static float lighten(float channel) {
        return WATER_LIGHTEN + (1f - WATER_LIGHTEN) * channel;
    }

    /**
     * Lava: a temperature ramp from dark crust over glowing orange to yellow-white. Moving lava is hotter, resting lava
     * crusts over, and every particle flickers on its own phase.
     */
    static int lavaColor(float speed, int sleepTicks, int hash, float timeTicks) {
        float crust = smoothstep(LAVA_CRUST_START_TICKS, LAVA_CRUST_FULL_TICKS, sleepTicks);
        float phase = (hash & 0xFFFF) / 65536f;
        float flicker = wave(timeTicks * LAVA_FLICKER_RATE + phase);
        float heat = clamp01(0.6f + 0.3f * smoothstep(0f, 3f, speed) + 0.1f * flicker - 0.6f * crust);
        float r;
        float g;
        float b;
        if (heat < 0.5f) {
            float t = heat * 2f;
            r = lerp(0.22f, 0.92f, t);
            g = lerp(0.05f, 0.28f, t);
            b = lerp(0.03f, 0.04f, t);
        } else {
            float t = heat * 2f - 1f;
            r = lerp(0.92f, 1.00f, t);
            g = lerp(0.28f, 0.82f, t);
            b = lerp(0.04f, 0.30f, t);
        }
        return argb(1f, r, g, b);
    }

    /** Slime: translucent green that brightens while it moves and bounces. */
    static int slimeColor(float speed) {
        float bounce = smoothstep(0.5f, 6f, speed);
        return argb(lerp(0.70f, 0.80f, bounce), lerp(0.40f, 0.68f, bounce), lerp(0.78f, 0.96f, bounce),
                lerp(0.32f, 0.52f, bounce));
    }

    /** Sand: opaque, every grain a little lighter, darker or warmer than the next, one in 16 a dark mineral grain. */
    static int sandColor(int hash) {
        float grain = 0.88f + 0.18f * ((hash & 0xFF) / 255f);
        float warm = ((hash >>> 8) & 0xFF) / 255f;
        float r = (0.85f + 0.05f * warm) * grain;
        float g = 0.79f * grain;
        float b = (0.58f - 0.06f * warm) * grain;
        if (((hash >>> 16) & 0xF) == 0) {
            r *= 0.7f;
            g *= 0.7f;
            b *= 0.7f;
        }
        return argb(1f, r, g, b);
    }

    /** Smooth periodic wave with period 1, between -1 and 1 (a triangle wave rounded by a cubic). */
    static float wave(float t) {
        float f = t - (float) Math.floor(t);
        float tri = 4f * Math.abs(f - 0.5f) - 1f;
        return tri * (1.5f - 0.5f * tri * tri);
    }

    /** Scrambles a particle index into well-mixed bits (murmur3 finaliser), for per-particle variation. */
    static int mix(int i) {
        int h = i * 0x9E3779B1;
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }

    private static int shadeColor(int argb, float shade) {
        int r = (int) (((argb >> 16) & 0xFF) * shade + 0.5f);
        int g = (int) (((argb >> 8) & 0xFF) * shade + 0.5f);
        int b = (int) ((argb & 0xFF) * shade + 0.5f);
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    static int argb(float a, float r, float g, float b) {
        return channel(a) << 24 | channel(r) << 16 | channel(g) << 8 | channel(b);
    }

    private static int channel(float v) {
        return (int) (clamp01(v) * 255f + 0.5f);
    }

    private static float smoothstep(float edge0, float edge1, float v) {
        float t = clamp01((v - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** One pass of a stable LSD radix sort over 8 bits of the keys, carrying the particle indices along. */
    private void radixPass(int[] srcKeys, int[] srcOrder, int[] dstKeys, int[] dstOrder, int count, int shift) {
        int[] bucket = radixCount;
        Arrays.fill(bucket, 0);
        for (int k = 0; k < count; k++) {
            bucket[(srcKeys[k] >>> shift) & 0xFF]++;
        }
        int sum = 0;
        for (int b = 0; b < bucket.length; b++) {
            int c = bucket[b];
            bucket[b] = sum;
            sum += c;
        }
        for (int k = 0; k < count; k++) {
            int key = srcKeys[k];
            int dst = bucket[(key >>> shift) & 0xFF]++;
            dstKeys[dst] = key;
            dstOrder[dst] = srcOrder[k];
        }
    }

    /** Grows the scratch arrays to hold {@code n} particles (only when the simulation grew, never per frame). */
    private void ensureCapacity(int n) {
        if (rx.length >= n) {
            return;
        }
        int size = Math.min(Math.max(n, rx.length * 2), ParticleSoA.MAX_PARTICLES);
        size = Math.max(size, n);
        rx = new float[size];
        ry = new float[size];
        rz = new float[size];
        order = new int[size];
        orderTmp = new int[size];
        keys = new int[size];
        keysTmp = new int[size];
    }
}
