// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.client.render;

import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_LAVA;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_SAND;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_SLIME;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_WATER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.physics.PbfSolver;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ParticleMesherTest {
    private static final int DAYLIGHT = 15 << 20;
    private static final int PLAINS_WATER = 0x3F76E4;
    private static final ParticleMesher.BlockEnvironment DAY = new ParticleMesher.BlockEnvironment() {
        @Override
        public int packedLight(int blockX, int blockY, int blockZ) {
            return DAYLIGHT;
        }

        @Override
        public int waterColor(int blockX, int blockY, int blockZ) {
            return PLAINS_WATER;
        }
    };

    @Test
    void visibleFacesWindCounterClockwiseTowardsTheCamera() {
        Random random = new Random(42);
        ParticleSoA particles = new ParticleSoA(2000);
        particles.setOrigin(100, 64, -50);
        for (int i = 0; i < particles.capacity; i++) {
            float speed = random.nextFloat() < 0.5f ? 0f : 9f;
            particles.add(random.nextFloat() * 20f - 10f, random.nextFloat() * 20f - 10f, random.nextFloat() * 20f - 10f,
                    (float) random.nextGaussian() * speed, (float) random.nextGaussian() * speed,
                    (float) random.nextGaussian() * speed, (byte) random.nextInt(ParticleSoA.MATERIAL_COUNT));
        }
        ParticleMesher mesher = new ParticleMesher();

        int visible = mesher.prepare(particles, 0f, 100.3, 64.7, -50.2, 0f, 0f, -1f);
        Recorder quads = new Recorder();
        mesher.emit(particles, DAY, quads, 0f);

        assertTrue(visible > 0);
        assertTrue(quads.quadCount() >= visible && quads.quadCount() <= 3 * visible,
                quads.quadCount() + " quads for " + visible + " particles");
        for (int q = 0; q < quads.quadCount(); q++) {
            float[] v = quads.quad(q);
            // Both triangles of the quad must face the camera at the origin (counter-clockwise front faces).
            assertTrue(facingOrigin(v, 0, 1, 2) > 0f, "first triangle of quad " + q + " faces away");
            assertTrue(facingOrigin(v, 0, 2, 3) > 0f, "second triangle of quad " + q + " faces away");
        }
    }

    @Test
    void aCubeShowsOnlyTheFacesTurnedTowardsTheCamera() {
        assertEquals(1, quadsOfOneParticle(0f, 0f, -5f), "straight ahead: front face only");
        assertEquals(2, quadsOfOneParticle(3f, 0f, -5f), "to the right: front and left face");
        assertEquals(3, quadsOfOneParticle(3f, 2f, -5f), "above to the right: three faces");
    }

    @Test
    void particlesBehindTheCameraAreSkipped() {
        ParticleSoA particles = new ParticleSoA(4);
        particles.add(0f, 0f, 5f, 0f, 0f, 0f, MATERIAL_WATER); // behind a camera looking towards -z
        particles.add(0f, 0f, 0.1f, 0f, 0f, 0f, MATERIAL_WATER); // beside the camera, may reach into view
        particles.add(0f, 0f, -5f, 0f, 0f, 0f, MATERIAL_WATER);

        int visible = new ParticleMesher().prepare(particles, 0f, 0, 0, 0, 0f, 0f, -1f);

        assertEquals(2, visible);
    }

    @Test
    void farParticlesAreDrawnFirst() {
        ParticleSoA particles = new ParticleSoA(8);
        float[] distances = {4f, 12f, 1f, 30f, 7.5f, 7.6f};
        for (float d : distances) {
            particles.add(0.3f, -0.2f, -d, 0f, 0f, 0f, MATERIAL_WATER);
        }
        ParticleMesher mesher = new ParticleMesher();

        int visible = mesher.prepare(particles, 0f, 0, 0, 0, 0f, 0f, -1f);

        assertEquals(distances.length, visible);
        int[] expected = {3, 1, 5, 4, 0, 2};
        for (int s = 0; s < visible; s++) {
            assertEquals(expected[s], mesher.drawOrder(s), "draw position " + s);
        }
    }

    @Test
    void positionsAreInterpolatedBetweenTheLastTwoTicks() {
        ParticleSoA particles = new ParticleSoA(1);
        particles.add(0f, 0f, -4f, 0f, 0f, 0f, MATERIAL_SAND);
        particles.posZ[0] = -6f; // the last tick moved it two blocks away
        ParticleMesher mesher = new ParticleMesher();

        mesher.prepare(particles, 0.25f, 0, 0, 0, 0f, 0f, -1f);
        Recorder quads = new Recorder();
        mesher.emit(particles, DAY, quads, 0f);

        assertEquals(1, quads.quadCount());
        float frontZ = -4.5f + ParticleMesher.CUBE_SIZE / 2f;
        for (int v = 0; v < 4; v++) {
            assertEquals(frontZ, quads.quad(0)[v * 3 + 2], 1e-5f);
        }
    }

    @Test
    void fastFluidsStretchAlongTheirVelocityAndKeepTheirVolume() {
        float fallSpeed = 9f;
        float[] slime = frontFaceExtent(MATERIAL_SLIME, -fallSpeed);
        float[] sand = frontFaceExtent(MATERIAL_SAND, -fallSpeed);

        float stretch = ParticleMesher.MAX_STRETCH;
        assertEquals(ParticleMesher.CUBE_SIZE * stretch, slime[1], 1e-4f, "height");
        assertEquals(ParticleMesher.CUBE_SIZE / (float) Math.sqrt(stretch), slime[0], 1e-4f, "width");
        float volume = slime[0] * slime[0] * slime[1];
        float cube = ParticleMesher.CUBE_SIZE * ParticleMesher.CUBE_SIZE * ParticleMesher.CUBE_SIZE;
        assertEquals(cube, volume, cube * 1e-3f, "volume");
        assertEquals(ParticleMesher.CUBE_SIZE, sand[0], 1e-5f, "sand does not stretch");
        assertEquals(ParticleMesher.CUBE_SIZE, sand[1], 1e-5f, "sand does not stretch");
        assertEquals(1f, ParticleMesher.stretchFactor(0.2f), "slow particles stay cubes");
    }

    @Test
    void waterFoamsWhereItSplashesAndIsolatedDropletsBecomeSmallWhiteSpray() {
        int calm = ParticleMesher.waterColor(PLAINS_WATER, ParticleMesher.waterFoam(0f, 0f, 0f));
        int splash = ParticleMesher.waterColor(PLAINS_WATER, ParticleMesher.waterFoam(3f, 0f, 7f));
        int flyingSpray = ParticleMesher.waterColor(PLAINS_WATER, ParticleMesher.waterFoam(3f, 1f, 0f));
        float freeFall = ParticleMesher.waterFoam(PbfSolver.MAX_SPEED, 0f, 0.25f);
        float restingDroplet = ParticleMesher.waterFoam(0f, 1f, 0f);

        assertTrue(blue(calm) > red(calm) + 80, "calm water is blue");
        assertTrue(alpha(calm) < 200, "calm water is translucent");
        assertTrue(red(splash) > 220 && green(splash) > 230, "splashing water is white foam");
        assertTrue(alpha(splash) > alpha(calm), "foam is more opaque");
        assertEquals(splash, flyingSpray, "flying spray looks like foam");
        assertEquals(ParticleMesher.SPEED_FOAM_MAX, freeFall, 1e-6f, "falling water only lightens");
        assertEquals(0f, restingDroplet, "a resting droplet keeps the water colour");
        assertEquals(1f, ParticleMesher.sprayAmount((byte) 0));
        assertEquals(0f, ParticleMesher.sprayAmount((byte) ParticleMesher.SPRAY_NEIGHBORS));
        assertEquals(0f, ParticleMesher.sprayAmount(ParticleSoA.NEIGHBORS_UNKNOWN), "fresh particles are not spray");

        assertEquals(ParticleMesher.CUBE_SIZE, sizeOfWaterParticle((byte) 6), 1e-5f);
        assertEquals(ParticleMesher.SPRAY_SIZE, sizeOfWaterParticle((byte) 0), 1e-5f);
    }

    @Test
    void lavaGlowsAtFullBrightnessAndCrustsOverWhileResting() {
        ParticleSoA particles = new ParticleSoA(1);
        particles.add(0f, 0f, -3f, 0f, 0f, 0f, MATERIAL_LAVA);
        ParticleMesher mesher = new ParticleMesher();
        mesher.prepare(particles, 0f, 0, 0, 0, 0f, 0f, -1f);
        Recorder quads = new Recorder();
        mesher.emit(particles, DAY, quads, 0f);
        assertEquals(ParticleMesher.FULL_BRIGHT, quads.lights.get(0));

        for (float time = 0f; time < 20f; time += 1.3f) {
            int flowing = ParticleMesher.lavaColor(2f, 0, 1234, time);
            int crusted = ParticleMesher.lavaColor(0f, ParticleMesher.LAVA_CRUST_FULL_TICKS, 1234, time);
            assertTrue(red(flowing) + green(flowing) > red(crusted) + green(crusted) + 150, "crust is darker");
            assertTrue(red(flowing) > green(flowing) && green(flowing) > blue(flowing), "lava glows orange");
            assertEquals(255, alpha(flowing));
        }
    }

    @Test
    void slimeIsTranslucentGreenAndSandGrainsVary() {
        int slime = ParticleMesher.slimeColor(0f);
        assertTrue(alpha(slime) < 255);
        assertTrue(green(slime) > red(slime) && green(slime) > blue(slime));
        assertTrue(green(ParticleMesher.slimeColor(6f)) > green(slime), "bouncing slime brightens");

        Set<Integer> grains = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            int sand = ParticleMesher.sandColor(ParticleMesher.mix(i));
            grains.add(sand);
            assertEquals(255, alpha(sand));
            assertTrue(red(sand) >= green(sand) && green(sand) > blue(sand), "sand stays sand-coloured");
        }
        assertTrue(grains.size() > 100, "only " + grains.size() + " different grains");
    }

    @Test
    void facesAreShadedLikeVanillaBlocks() {
        assertEquals(1.0f, ParticleMesher.faceShade(0f, 1f, 0f), 1e-6f);
        assertEquals(0.5f, ParticleMesher.faceShade(0f, -1f, 0f), 1e-6f);
        assertEquals(0.8f, ParticleMesher.faceShade(0f, 0f, 1f), 1e-6f);
        assertEquals(0.6f, ParticleMesher.faceShade(-1f, 0f, 0f), 1e-6f);
    }

    @Test
    void meshingAFrameAllocatesNothing() {
        Random random = new Random(7);
        ParticleSoA particles = new ParticleSoA(16384);
        for (int i = 0; i < particles.capacity; i++) {
            particles.add(random.nextFloat() * 40f - 20f, random.nextFloat() * 10f, random.nextFloat() * 40f - 20f,
                    random.nextFloat() * 6f - 3f, random.nextFloat() * 6f - 3f, random.nextFloat() * 6f - 3f,
                    (byte) (i & 3));
            particles.neighbors[i] = (byte) (i % 8);
        }
        ParticleMesher mesher = new ParticleMesher();
        VertexCounter sink = new VertexCounter();
        for (int i = 0; i < 50; i++) {
            mesher.prepare(particles, i / 50f, 0.5, 5, 0.5, 0.6f, -0.48f, -0.64f);
            mesher.emit(particles, DAY, sink, i);
        }

        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            sink.vertices = 0;
            mesher.prepare(particles, i / 20f, 0.5, 5, 0.5, 0.6f, -0.48f, -0.64f);
            mesher.emit(particles, DAY, sink, i);
        }
        double msPerFrame = (System.nanoTime() - start) / 20 / 1.0e6;
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        System.out.printf("ParticleMesher @ %d particles: %.2f ms per frame, %d vertices in the last frame, "
                + "%d bytes allocated in 20 frames%n", particles.activeCount, msPerFrame, sink.vertices, allocated);

        assertEquals(0L, allocated, "meshing must not allocate");
    }

    private static int quadsOfOneParticle(float x, float y, float z) {
        ParticleSoA particles = new ParticleSoA(1);
        particles.add(x, y, z, 0f, 0f, 0f, MATERIAL_SAND);
        ParticleMesher mesher = new ParticleMesher();
        mesher.prepare(particles, 0f, 0, 0, 0, 0f, 0f, -1f);
        Recorder quads = new Recorder();
        mesher.emit(particles, DAY, quads, 0f);
        return quads.quadCount();
    }

    /** Width (x) and height (y) of the face a particle straight ahead shows the camera, moving at {@code vy}. */
    private static float[] frontFaceExtent(byte material, float vy) {
        ParticleSoA particles = new ParticleSoA(1);
        particles.add(0f, 0f, -5f, 0f, vy, 0f, material);
        float[] face = frontFace(particles);
        return new float[] {extent(face, 0), extent(face, 1)};
    }

    private static float sizeOfWaterParticle(byte neighbors) {
        ParticleSoA particles = new ParticleSoA(1);
        particles.add(0f, 0f, -5f, 0f, 0f, 0f, MATERIAL_WATER);
        particles.neighbors[0] = neighbors;
        return extent(frontFace(particles), 0);
    }

    /** The only quad of a single particle straight ahead of the camera. */
    private static float[] frontFace(ParticleSoA particles) {
        ParticleMesher mesher = new ParticleMesher();
        mesher.prepare(particles, 0f, 0, 0, 0, 0f, 0f, -1f);
        Recorder quads = new Recorder();
        mesher.emit(particles, DAY, quads, 0f);
        assertEquals(1, quads.quadCount());
        return quads.quad(0);
    }

    /** Extent of a quad along one axis (0 = x, 1 = y, 2 = z). */
    private static float extent(float[] quad, int axis) {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int v = 0; v < 4; v++) {
            min = Math.min(min, quad[v * 3 + axis]);
            max = Math.max(max, quad[v * 3 + axis]);
        }
        return max - min;
    }

    /** Positive if triangle (a, b, c) of quad {@code v} is wound counter-clockwise as seen from the origin. */
    private static float facingOrigin(float[] v, int a, int b, int c) {
        float e1x = v[b * 3] - v[a * 3];
        float e1y = v[b * 3 + 1] - v[a * 3 + 1];
        float e1z = v[b * 3 + 2] - v[a * 3 + 2];
        float e2x = v[c * 3] - v[a * 3];
        float e2y = v[c * 3 + 1] - v[a * 3 + 1];
        float e2z = v[c * 3 + 2] - v[a * 3 + 2];
        float nx = e1y * e2z - e1z * e2y;
        float ny = e1z * e2x - e1x * e2z;
        float nz = e1x * e2y - e1y * e2x;
        return -(nx * v[a * 3] + ny * v[a * 3 + 1] + nz * v[a * 3 + 2]);
    }

    private static int alpha(int argb) {
        return argb >>> 24;
    }

    private static int red(int argb) {
        return (argb >> 16) & 0xFF;
    }

    private static int green(int argb) {
        return (argb >> 8) & 0xFF;
    }

    private static int blue(int argb) {
        return argb & 0xFF;
    }

    /** Keeps every vertex. */
    private static final class Recorder implements ParticleMesher.VertexSink {
        final List<Float> coords = new ArrayList<>();
        final List<Integer> lights = new ArrayList<>();

        @Override
        public void vertex(float x, float y, float z, int argb, int packedLight) {
            coords.add(x);
            coords.add(y);
            coords.add(z);
            lights.add(packedLight);
        }

        int quadCount() {
            return coords.size() / 12;
        }

        float[] quad(int q) {
            float[] v = new float[12];
            for (int i = 0; i < 12; i++) {
                v[i] = coords.get(q * 12 + i);
            }
            return v;
        }
    }

    /** Counts vertices without allocating. */
    private static final class VertexCounter implements ParticleMesher.VertexSink {
        int vertices;

        @Override
        public void vertex(float x, float y, float z, int argb, int packedLight) {
            vertices++;
        }
    }
}
