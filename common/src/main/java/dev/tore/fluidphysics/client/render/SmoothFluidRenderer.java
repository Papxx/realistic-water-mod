// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.client.render;

import dev.tore.fluidphysics.physics.ParticleSoA;

/**
 * Wet-look shading of the smooth fluid surface (Plan.md §8.3): turns the sorted triangles of a
 * {@link MarchingCubesMesher} into coloured, lit quads for the vertex sink. Plain Java without any rendering API, so it
 * can be unit tested; {@link ParticleBatchRenderer} feeds its output into a vertex buffer.
 *
 * <p>All shading happens here on the CPU, once per vertex, because the {@code text_background} render type the surface
 * is drawn with (Plan.md §8.4.1) only takes a position, a colour and lightmap coordinates and its shader has no
 * lighting. Per vertex:
 * <ul>
 *   <li><b>Beer-Lambert absorption:</b> the mesher's thickness (0 = thin edge, 1 = deep pool) blends a thin, light,
 *       more transparent colour into a deep, dark, more opaque one along the absorbed fraction
 *       {@code 1 - exp(-k t)}, so the colour deepens quickly at first and then saturates, like light passing through
 *       more and more water.</li>
 *   <li><b>Diffuse:</b> a mild directional term from the fixed light direction {@code L}, so the shape reads.</li>
 *   <li><b>Fresnel</b> (Schlick, {@code F0 = 0.04}): at grazing angles more of the sky is reflected, so the surface
 *       turns lighter and more opaque towards its silhouette.</li>
 *   <li><b>Blinn-Phong specular:</b> {@code pow(N.H, n)} with the halfway vector {@code H = normalize(L + V)}, added to
 *       red, green and blue alike (white highlights) and raising the opacity, so the highlights on wave crests and
 *       droplet edges shine instead of being washed out by the blending.</li>
 * </ul>
 * The brightness of the world (day, night, torches) is applied afterwards by the shader through the packed light: the
 * block light around each water and slime vertex, full brightness for glowing lava.
 *
 * <p>Each triangle {@code (v0, v1, v2)} is written as the quad {@code (v0, v1, v2, v2)}, since the render type draws
 * quads: the duplicated last vertex makes the quad's second triangle empty. The triangles arrive sorted far to near,
 * which the translucent blending needs.
 *
 * <p>Allocation-free once the per-vertex scratch arrays have grown to the largest mesh. Not thread-safe: render thread
 * only.
 */
public final class SmoothFluidRenderer {
    /** Vertices written per triangle: the quad {@code (v0, v1, v2, v2)}. */
    public static final int VERTICES_PER_TRIANGLE = 4;

    // Light direction L of Plan.md §8.3, normalised (it is the same for every vertex and every frame).
    private static final float LIGHT_LENGTH = (float) Math.sqrt(0.35 * 0.35 + 0.9 * 0.9 + 0.25 * 0.25);
    static final float LIGHT_X = 0.35f / LIGHT_LENGTH;
    static final float LIGHT_Y = 0.9f / LIGHT_LENGTH;
    static final float LIGHT_Z = 0.25f / LIGHT_LENGTH;

    /** Schlick's reflectance of water seen head-on (Plan.md §8.3). */
    static final float FRESNEL_F0 = 0.04f;
    /** How far the fully grazing Fresnel reflection blends the colour towards the reflected sky. */
    static final float FRESNEL_REFLECT = 0.3f;
    /** How far the fully grazing Fresnel reflection raises the opacity towards 1. */
    static final float FRESNEL_OPACITY = 0.4f;
    /** Brightness of a surface facing away from the light; facing it fully adds the rest up to 1. */
    static final float DIFFUSE_AMBIENT = 0.7f;
    /** Opacity a full highlight reaches (Plan.md §8.3). */
    static final float HIGHLIGHT_ALPHA = 0.96f;

    /**
     * Water (Plan.md §8.3): thin RGB(38, 198, 208) at alpha 0.58 to deep RGB(6, 102, 115) at alpha 0.86, a pale sky
     * blue reflected at grazing angles, and a sharp white highlight ({@code pow(N.H, 48) * 0.85}). With absorption
     * 2.2, thickness 0.3 already shows 54 % of the deep colour.
     */
    static final GlossyLook WATER = new GlossyLook(38, 198, 208, 0.58f, 6, 102, 115, 0.86f,
            0.80f, 0.90f, 0.97f, 2.2f, 48, 0.85f);
    /**
     * Slime: translucent emerald green, lighter where thin and darker where deep, with a stronger and tighter gloss
     * than water.
     */
    static final GlossyLook SLIME = new GlossyLook(122, 216, 102, 0.62f, 40, 138, 48, 0.85f,
            0.86f, 0.97f, 0.86f, 1.6f, 96, 1.0f);

    /**
     * Lava heat (see {@link ParticleMesher#lavaRamp}) at a thin edge, where it has cooled to a dark crust, and in a
     * thick body, which glows orange-yellow. The heat rises with the thickness up to {@link #LAVA_CORE_THICKNESS}.
     */
    static final float LAVA_EDGE_HEAT = 0.12f;
    static final float LAVA_CORE_HEAT = 0.9f;
    static final float LAVA_CORE_THICKNESS = 0.6f;
    /** How much the flicker (-1..1) moves the heat. */
    static final float LAVA_FLICKER_HEAT = 0.08f;
    /** Glowing lava keeps most of its brightness facing away from the light; a little shading still shows its shape. */
    static final float LAVA_SHADE_BASE = 0.75f;
    /** Lava is not wet: a weak, broad highlight only. */
    static final int LAVA_SPECULAR_EXPONENT = 24;
    static final float LAVA_SPECULAR_STRENGTH = 0.18f;
    /**
     * The flicker is two travelling waves over the world position: cycles per tick of each (one per second and one per
     * 3.3 seconds) and cycles per block along x, y and z, so neighbouring vertices glow alike and the pattern drifts
     * slowly across a lava pool instead of blinking.
     */
    private static final float LAVA_FLICKER_RATE = 0.05f;
    private static final float LAVA_FLICKER_RATE_SLOW = 0.015f;
    private static final double LAVA_FLICKER_X = 0.37;
    private static final double LAVA_FLICKER_Y = 0.23;
    private static final double LAVA_FLICKER_Z = 0.29;
    private static final double LAVA_FLICKER_SLOW_X = -0.17;
    private static final double LAVA_FLICKER_SLOW_Z = 0.21;
    /** Share of the faster wave in the flicker; the slow wave has the rest. */
    private static final float LAVA_FLICKER_FAST_SHARE = 0.6f;

    /**
     * Water and slime take the light of the block a little inside the fluid: {@code p - N * depth}, lifted slightly.
     * The surface touches floors and walls exactly on block faces, where rounding would otherwise pick the solid,
     * unlit block for some vertices and leave dark, flickering seams along the contact line; the fluid's own particles
     * always sit in open blocks. One field cell deep, and lifted by 1/64 block for vertices whose normal lies flat.
     */
    static final float LIGHT_PROBE_DEPTH = 0.125f;
    static final float LIGHT_PROBE_LIFT = 1f / 64f;
    /** Vertices closer to the camera than this (squared, in blocks) are shaded as seen head-on. */
    private static final float MIN_VIEW_DISTANCE_SQ = 1e-8f;
    private static final float MIN_HALFWAY_LENGTH_SQ = 1e-12f;

    // Per-vertex results, indexed by mesh vertex: shaded once, used by every triangle that shares the vertex.
    private int[] colors = new int[0];
    private int[] lights = new int[0];

    /**
     * Shades every vertex of the sorted mesh (Plan.md §8.3) and writes each triangle as the quad
     * {@code (v0, v1, v2, v2)} to the sink, far to near.
     *
     * @param mesh      a mesher after {@link MarchingCubesMesher#sort()}; its positions are relative to the camera
     * @param env       light of world blocks
     * @param camX      camera position in world coordinates, the same the mesher began the frame with
     * @param timeTicks a clock in ticks for the lava flicker; only its changes matter
     * @return the number of vertices written, {@link #VERTICES_PER_TRIANGLE} per triangle
     */
    public int emit(MarchingCubesMesher mesh, ParticleMesher.BlockEnvironment env, ParticleMesher.VertexSink sink,
                    double camX, double camY, double camZ, float timeTicks) {
        int vertexCount = mesh.vertexCount();
        ensureCapacity(vertexCount);
        int[] color = colors;
        int[] light = lights;
        for (int v = 0; v < vertexCount; v++) {
            float x = mesh.vertexX(v);
            float y = mesh.vertexY(v);
            float z = mesh.vertexZ(v);
            float nx = mesh.normalX(v);
            float ny = mesh.normalY(v);
            float nz = mesh.normalZ(v);
            // The camera sits at the origin, so the view vector is the normalised negated position.
            float vx;
            float vy;
            float vz;
            float distanceSq = x * x + y * y + z * z;
            if (distanceSq > MIN_VIEW_DISTANCE_SQ) {
                float inv = -1f / (float) Math.sqrt(distanceSq);
                vx = x * inv;
                vy = y * inv;
                vz = z * inv;
            } else {
                vx = nx;
                vy = ny;
                vz = nz;
            }
            float thickness = mesh.thickness(v);
            switch (mesh.vertexMaterial(v)) {
                case ParticleSoA.MATERIAL_WATER -> {
                    color[v] = shadeWater(nx, ny, nz, vx, vy, vz, thickness);
                    light[v] = probeLight(env, camX, camY, camZ, x, y, z, nx, ny, nz);
                }
                case ParticleSoA.MATERIAL_LAVA -> {
                    float flicker = lavaFlicker(camX + x, camY + y, camZ + z, timeTicks);
                    color[v] = shadeLava(nx, ny, nz, vx, vy, vz, thickness, flicker);
                    light[v] = ParticleMesher.FULL_BRIGHT;
                }
                case ParticleSoA.MATERIAL_SLIME -> {
                    color[v] = shadeSlime(nx, ny, nz, vx, vy, vz, thickness);
                    light[v] = probeLight(env, camX, camY, camZ, x, y, z, nx, ny, nz);
                }
                default -> {
                    // Sand is never meshed; anything else stays invisible rather than guessing a look.
                    color[v] = 0;
                    light[v] = 0;
                }
            }
        }
        int triangles = mesh.triangleCount();
        for (int t = 0; t < triangles; t++) {
            int a = mesh.triangleVertex(t, 0);
            int b = mesh.triangleVertex(t, 1);
            int c = mesh.triangleVertex(t, 2);
            put(mesh, sink, a, color, light);
            put(mesh, sink, b, color, light);
            put(mesh, sink, c, color, light);
            put(mesh, sink, c, color, light);
        }
        return triangles * VERTICES_PER_TRIANGLE;
    }

    private static void put(MarchingCubesMesher mesh, ParticleMesher.VertexSink sink, int v, int[] color,
                            int[] light) {
        sink.vertex(mesh.vertexX(v), mesh.vertexY(v), mesh.vertexZ(v), color[v], light[v]);
    }

    /** Packed light of a water or slime vertex at camera-relative ({@code x}, {@code y}, {@code z}). */
    private static int probeLight(ParticleMesher.BlockEnvironment env, double camX, double camY, double camZ,
                                  float x, float y, float z, float nx, float ny, float nz) {
        return env.packedLight((int) Math.floor(camX + (x - nx * LIGHT_PROBE_DEPTH)),
                (int) Math.floor(camY + (y - ny * LIGHT_PROBE_DEPTH + LIGHT_PROBE_LIFT)),
                (int) Math.floor(camZ + (z - nz * LIGHT_PROBE_DEPTH)));
    }

    /**
     * Water colour of a surface point.
     *
     * @param nx        unit outward surface normal
     * @param vx        unit vector from the point towards the camera
     * @param thickness 0 = thin edge or droplet, 1 = deep pool
     */
    static int shadeWater(float nx, float ny, float nz, float vx, float vy, float vz, float thickness) {
        return shadeGlossy(WATER, nx, ny, nz, vx, vy, vz, thickness);
    }

    /** Slime colour of a surface point; parameters as {@link #shadeWater}. */
    static int shadeSlime(float nx, float ny, float nz, float vx, float vy, float vz, float thickness) {
        return shadeGlossy(SLIME, nx, ny, nz, vx, vy, vz, thickness);
    }

    /**
     * The translucent wet look shared by water and slime: Beer-Lambert absorption, diffuse, Fresnel and Blinn-Phong
     * specular (see the class comment). Robust against broken input: every dot product is clamped and NaN counts as
     * 0, so the channels stay in range and the result keeps at least the thin colour's opacity.
     */
    static int shadeGlossy(GlossyLook look, float nx, float ny, float nz, float vx, float vy, float vz,
                           float thickness) {
        float depth = look.absorption(thickness);
        float lit = diffuse(nx * LIGHT_X + ny * LIGHT_Y + nz * LIGHT_Z);
        float r = lerp(look.thinR, look.deepR, depth) * lit;
        float g = lerp(look.thinG, look.deepG, depth) * lit;
        float b = lerp(look.thinB, look.deepB, depth) * lit;
        float a = lerp(look.thinA, look.deepA, depth);

        float f = fresnel(nx * vx + ny * vy + nz * vz);
        float reflect = f * FRESNEL_REFLECT;
        r = lerp(r, look.reflectionR, reflect);
        g = lerp(g, look.reflectionG, reflect);
        b = lerp(b, look.reflectionB, reflect);
        a = lerp(a, 1f, f * FRESNEL_OPACITY);

        float highlight = specular(halfwayDot(nx, ny, nz, vx, vy, vz), look.specularExponent);
        float spec = highlight * look.specularStrength;
        r += spec;
        g += spec;
        b += spec;
        a = Math.max(a, lerp(a, HIGHLIGHT_ALPHA, highlight));
        return argb(a, r, g, b);
    }

    /**
     * Lava colour of a surface point: the temperature ramp of the cubes, cooled to a dark crust at thin edges, glowing
     * orange-yellow where thick, with a weak highlight. Always opaque.
     *
     * @param nx        unit outward surface normal
     * @param vx        unit vector from the point towards the camera
     * @param thickness 0 = thin edge or droplet, 1 = deep pool
     * @param flicker   -1..1, see {@link #lavaFlicker}
     */
    static int shadeLava(float nx, float ny, float nz, float vx, float vy, float vz, float thickness, float flicker) {
        float core = smoothstep(0f, LAVA_CORE_THICKNESS, clamp01(thickness));
        float heat = clamp01(lerp(LAVA_EDGE_HEAT, LAVA_CORE_HEAT, core) + LAVA_FLICKER_HEAT * clampSigned(flicker));
        int ramp = ParticleMesher.lavaRamp(heat);
        float glow = LAVA_SHADE_BASE + (1f - LAVA_SHADE_BASE) * clamp01(nx * LIGHT_X + ny * LIGHT_Y + nz * LIGHT_Z);
        float spec = specular(halfwayDot(nx, ny, nz, vx, vy, vz), LAVA_SPECULAR_EXPONENT) * LAVA_SPECULAR_STRENGTH;
        float r = ((ramp >> 16) & 0xFF) / 255f * glow + spec;
        float g = ((ramp >> 8) & 0xFF) / 255f * glow + spec;
        float b = (ramp & 0xFF) / 255f * glow + spec;
        return argb(1f, r, g, b);
    }

    /**
     * Slow procedural flicker of glowing lava at a world position, -1..1: two waves of different speed and direction
     * travelling across the world, so it is smooth in space and time and needs no per-particle state.
     */
    static float lavaFlicker(double worldX, double worldY, double worldZ, float timeTicks) {
        // Phases in double: world coordinates can be large, only their fractions matter.
        double fast = worldX * LAVA_FLICKER_X + worldY * LAVA_FLICKER_Y + worldZ * LAVA_FLICKER_Z;
        double slow = worldX * LAVA_FLICKER_SLOW_X + worldZ * LAVA_FLICKER_SLOW_Z;
        float fastPhase = (float) (fast - Math.floor(fast));
        float slowPhase = (float) (slow - Math.floor(slow));
        float flicker = LAVA_FLICKER_FAST_SHARE * ParticleMesher.wave(timeTicks * LAVA_FLICKER_RATE + fastPhase)
                + (1f - LAVA_FLICKER_FAST_SHARE) * ParticleMesher.wave(timeTicks * LAVA_FLICKER_RATE_SLOW - slowPhase);
        return clampSigned(flicker);
    }

    /** Schlick's Fresnel term {@code F0 + (1 - F0) (1 - max(N.V, 0))^4}, 0.04 head-on up to 1 at grazing angles. */
    static float fresnel(float nDotV) {
        float c = 1f - clamp01(nDotV);
        float c2 = c * c;
        return FRESNEL_F0 + (1f - FRESNEL_F0) * c2 * c2;
    }

    /** Diffuse brightness for {@code N.L}: {@link #DIFFUSE_AMBIENT} facing away from the light, 1 facing it. */
    static float diffuse(float nDotL) {
        return DIFFUSE_AMBIENT + (1f - DIFFUSE_AMBIENT) * clamp01(nDotL);
    }

    /** Blinn-Phong highlight {@code max(N.H, 0)^exponent}, 0..1. */
    static float specular(float nDotH, int exponent) {
        return powInt(clamp01(nDotH), exponent);
    }

    /** {@code N.H} with the halfway vector {@code H = normalize(L + V)}; 0 when it is undefined. */
    static float halfwayDot(float nx, float ny, float nz, float vx, float vy, float vz) {
        float hx = LIGHT_X + vx;
        float hy = LIGHT_Y + vy;
        float hz = LIGHT_Z + vz;
        float lengthSq = hx * hx + hy * hy + hz * hz;
        if (!(lengthSq > MIN_HALFWAY_LENGTH_SQ) || lengthSq == Float.POSITIVE_INFINITY) {
            return 0f; // V points straight away from the light, or broken input
        }
        return (nx * hx + ny * hy + nz * hz) / (float) Math.sqrt(lengthSq);
    }

    /** {@code base^exponent} by repeated squaring (a handful of multiplications instead of {@code Math.pow}). */
    static float powInt(float base, int exponent) {
        float result = 1f;
        float square = base;
        for (int e = exponent; e > 0; e >>= 1) {
            if ((e & 1) != 0) {
                result *= square;
            }
            square *= square;
        }
        return result;
    }

    static int argb(float a, float r, float g, float b) {
        return channel(a) << 24 | channel(r) << 16 | channel(g) << 8 | channel(b);
    }

    private static int channel(float v) {
        return (int) (clamp01(v) * 255f + 0.5f);
    }

    /** Clamps to 0..1; NaN becomes 0. */
    static float clamp01(float v) {
        return v > 0f ? (v < 1f ? v : 1f) : 0f;
    }

    /** Clamps to -1..1; NaN becomes 0. */
    private static float clampSigned(float v) {
        return v > 0f ? Math.min(v, 1f) : (v < 0f ? Math.max(v, -1f) : 0f);
    }

    private static float smoothstep(float edge0, float edge1, float v) {
        float t = clamp01((v - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * The constants of one translucent material's look (water, slime). Colours are given as 0..255 like the plan,
     * opacities and the reflected sky colour as 0..1.
     */
    static final class GlossyLook {
        final float thinR;
        final float thinG;
        final float thinB;
        final float thinA;
        final float deepR;
        final float deepG;
        final float deepB;
        final float deepA;
        final float reflectionR;
        final float reflectionG;
        final float reflectionB;
        /** Beer-Lambert coefficient {@code k} over the whole thickness range 0..1. */
        final float absorptionCoefficient;
        /** {@code 1 / (1 - exp(-k))}, so thickness 1 gives exactly the deep colour. */
        private final double absorptionScale;
        final int specularExponent;
        final float specularStrength;

        GlossyLook(int thinR, int thinG, int thinB, float thinA, int deepR, int deepG, int deepB, float deepA,
                   float reflectionR, float reflectionG, float reflectionB, float absorptionCoefficient,
                   int specularExponent, float specularStrength) {
            this.thinR = thinR / 255f;
            this.thinG = thinG / 255f;
            this.thinB = thinB / 255f;
            this.thinA = thinA;
            this.deepR = deepR / 255f;
            this.deepG = deepG / 255f;
            this.deepB = deepB / 255f;
            this.deepA = deepA;
            this.reflectionR = reflectionR;
            this.reflectionG = reflectionG;
            this.reflectionB = reflectionB;
            this.absorptionCoefficient = absorptionCoefficient;
            this.absorptionScale = 1.0 / (1.0 - Math.exp(-absorptionCoefficient));
            this.specularExponent = specularExponent;
            this.specularStrength = specularStrength;
        }

        /**
         * Share of the deep colour at a thickness, 0..1 (Beer-Lambert): the absorbed fraction {@code 1 - exp(-k t)},
         * normalised so thickness 0 gives the thin and thickness 1 the deep colour exactly.
         *
         * @param thickness 0..1; NaN counts as 0
         */
        float absorption(float thickness) {
            return (float) ((1.0 - Math.exp(-absorptionCoefficient * clamp01(thickness))) * absorptionScale);
        }
    }

    /** Grows the per-vertex scratch arrays to hold {@code n} vertices (only when a mesh is larger than any before). */
    private void ensureCapacity(int n) {
        if (colors.length >= n) {
            return;
        }
        int size = Math.max(n, colors.length * 2);
        colors = new int[size];
        lights = new int[size];
    }
}
