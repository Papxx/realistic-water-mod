// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.client.render;

import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_LAVA;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_SLIME;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_WATER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tore.fluidphysics.physics.ParticleSoA;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmoothFluidRendererTest {
    private static final int DAYLIGHT = 15 << 20;
    private static final ParticleMesher.BlockEnvironment DAY = new ParticleMesher.BlockEnvironment() {
        @Override
        public int packedLight(int blockX, int blockY, int blockZ) {
            return DAYLIGHT;
        }

        @Override
        public int waterColor(int blockX, int blockY, int blockZ) {
            return 0x3F76E4;
        }
    };
    /** Lowest and highest opacity the translucent looks may have: thin water's 0.58 and a full highlight's 0.96. */
    private static final int THIN_WATER_ALPHA = Math.round(0.58f * 255f);
    private static final int HIGHLIGHT_ALPHA = Math.round(SmoothFluidRenderer.HIGHLIGHT_ALPHA * 255f);

    // Particle origin and camera of the end-to-end tests: the camera looks down at the blobs from the south.
    private static final int ORIGIN_X = 10;
    private static final int ORIGIN_Y = 64;
    private static final int ORIGIN_Z = -20;
    private static final double CAM_X = ORIGIN_X + 0.5;
    private static final double CAM_Y = ORIGIN_Y + 2.5;
    private static final double CAM_Z = ORIGIN_Z + 4.5;
    private static final float FWD_Y = (float) (-2.0 / Math.sqrt(20.0));
    private static final float FWD_Z = (float) (-4.0 / Math.sqrt(20.0));
    /** One field cell, the distance between neighbouring surface vertices. */
    private static final double CELL = FluidDensityField.CELL_SIZE;

    @Test
    void theLightDirectionIsNormalised() {
        float x = SmoothFluidRenderer.LIGHT_X;
        float y = SmoothFluidRenderer.LIGHT_Y;
        float z = SmoothFluidRenderer.LIGHT_Z;
        assertEquals(1f, x * x + y * y + z * z, 1e-6f);
        assertTrue(y > x && x > z && z > 0f, "light from above, a little from the east and south");
    }

    @Test
    void aHighlightIsBrighterAndMoreOpaqueThanTheSameSurfaceTurnedAway() {
        // The camera looks along -z, so V points along +z; N = H is the mirror direction of the light.
        float[] h = normalize(SmoothFluidRenderer.LIGHT_X, SmoothFluidRenderer.LIGHT_Y,
                SmoothFluidRenderer.LIGHT_Z + 1f);
        // Turned away from H but straight towards the camera, so the Fresnel term can not favour it.
        int waterHighlight = SmoothFluidRenderer.shadeWater(h[0], h[1], h[2], 0f, 0f, 1f, 0.5f);
        int waterAway = SmoothFluidRenderer.shadeWater(0f, 0f, 1f, 0f, 0f, 1f, 0.5f);
        int slimeHighlight = SmoothFluidRenderer.shadeSlime(h[0], h[1], h[2], 0f, 0f, 1f, 0.5f);
        int slimeAway = SmoothFluidRenderer.shadeSlime(0f, 0f, 1f, 0f, 0f, 1f, 0.5f);

        assertTrue(brightness(waterHighlight) > brightness(waterAway) + 200,
                "water highlight " + hex(waterHighlight) + " vs " + hex(waterAway));
        assertTrue(alpha(waterHighlight) > alpha(waterAway) + 20);
        assertEquals(HIGHLIGHT_ALPHA, alpha(waterHighlight), 1, "a full highlight reaches the highlight opacity");
        assertTrue(red(waterHighlight) > 220 && green(waterHighlight) > 220, "the highlight is white");
        assertTrue(brightness(slimeHighlight) > brightness(slimeAway) + 200);
        assertTrue(alpha(slimeHighlight) > alpha(slimeAway));

        assertEquals(1f, SmoothFluidRenderer.specular(1f, SmoothFluidRenderer.WATER.specularExponent), 1e-6f);
        assertEquals(0f, SmoothFluidRenderer.specular(-0.5f, SmoothFluidRenderer.WATER.specularExponent));
        assertTrue(SmoothFluidRenderer.specular(0.9f, SmoothFluidRenderer.SLIME.specularExponent)
                < SmoothFluidRenderer.specular(0.9f, SmoothFluidRenderer.WATER.specularExponent),
                "slime's gloss is tighter");
        assertEquals((float) Math.pow(0.97, 48), SmoothFluidRenderer.powInt(0.97f, 48), 1e-6f);
        assertEquals(1f, SmoothFluidRenderer.powInt(0.3f, 0));
    }

    @Test
    void deepWaterIsDarkerAndMoreOpaqueThanThinWater() {
        int thin = SmoothFluidRenderer.shadeWater(0f, 0f, 1f, 0f, 0f, 1f, 0f);
        int middle = SmoothFluidRenderer.shadeWater(0f, 0f, 1f, 0f, 0f, 1f, 0.3f);
        int deep = SmoothFluidRenderer.shadeWater(0f, 0f, 1f, 0f, 0f, 1f, 1f);

        assertTrue(brightness(deep) < brightness(thin) - 100, hex(deep) + " vs " + hex(thin));
        assertTrue(alpha(deep) > alpha(thin) + 50);
        assertTrue(brightness(middle) < brightness(thin) && brightness(middle) > brightness(deep));
        assertEquals(THIN_WATER_ALPHA, alpha(thin), 3, "thin water is mostly see-through");
        assertEquals(Math.round(0.86f * 255f), alpha(deep), 3);
        for (int color : new int[] {thin, middle, deep}) {
            assertTrue(green(color) > red(color) + 50 && blue(color) > red(color) + 50, "turquoise " + hex(color));
        }

        // Beer-Lambert: the deep colour takes over quickly and then saturates.
        assertEquals(0f, SmoothFluidRenderer.WATER.absorption(0f), 1e-6f);
        assertEquals(1f, SmoothFluidRenderer.WATER.absorption(1f), 1e-6f);
        assertTrue(SmoothFluidRenderer.WATER.absorption(0.3f) > 0.45f);
        assertEquals(1f, SmoothFluidRenderer.WATER.absorption(7f), 1e-6f, "thickness is clamped");
    }

    @Test
    void fresnelRaisesOpacityAndBrightnessAtGrazingAngles() {
        assertEquals(SmoothFluidRenderer.FRESNEL_F0, SmoothFluidRenderer.fresnel(1f), 1e-6f);
        assertEquals(1f, SmoothFluidRenderer.fresnel(0f), 1e-6f);
        assertEquals(1f, SmoothFluidRenderer.fresnel(-0.4f), 1e-6f, "a normal turned away counts as grazing");
        assertTrue(SmoothFluidRenderer.fresnel(0.2f) > SmoothFluidRenderer.fresnel(0.6f));

        // Seen straight on versus edge-on; the grazing normal points west, away from the light and its highlight.
        int headOn = SmoothFluidRenderer.shadeWater(0f, 0f, 1f, 0f, 0f, 1f, 1f);
        int grazing = SmoothFluidRenderer.shadeWater(-1f, 0f, 0f, 0f, 0f, 1f, 1f);

        assertTrue(alpha(grazing) > alpha(headOn) + 10, hex(grazing) + " vs " + hex(headOn));
        assertTrue(brightness(grazing) > brightness(headOn), hex(grazing) + " vs " + hex(headOn));
    }

    @Test
    void lavaGlowsWithADarkCrustAtItsThinEdges() {
        int edge = SmoothFluidRenderer.shadeLava(0f, 0f, 1f, 0f, 0f, 1f, 0.02f, 0f);
        int core = SmoothFluidRenderer.shadeLava(0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f);

        assertTrue(brightness(edge) + 150 < brightness(core), hex(edge) + " vs " + hex(core));
        assertEquals(255, alpha(edge));
        assertEquals(255, alpha(core));
        assertTrue(red(core) > green(core) && green(core) > blue(core), "orange-yellow " + hex(core));
        assertTrue(red(core) > 180, "glowing " + hex(core));

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float time = 0f; time < 200f; time += 0.7f) {
            float flicker = SmoothFluidRenderer.lavaFlicker(12.3, 64.1, -7.8, time);
            assertTrue(flicker >= -1f && flicker <= 1f, "flicker " + flicker);
            min = Math.min(min, flicker);
            max = Math.max(max, flicker);
        }
        assertTrue(max - min > 0.5f, "lava flickers over time");
        float here = SmoothFluidRenderer.lavaFlicker(12.3, 64.1, -7.8, 40f);
        float beside = SmoothFluidRenderer.lavaFlicker(12.3 + CELL, 64.1, -7.8, 40f);
        assertEquals(here, beside, 0.25f, "neighbouring vertices glow alike");
    }

    @Test
    void slimeIsTranslucentGlossyGreen() {
        int thin = SmoothFluidRenderer.shadeSlime(0f, 0f, 1f, 0f, 0f, 1f, 0f);
        int deep = SmoothFluidRenderer.shadeSlime(0f, 0f, 1f, 0f, 0f, 1f, 1f);

        for (int color : new int[] {thin, deep}) {
            assertTrue(green(color) > red(color) && green(color) > blue(color), "green " + hex(color));
            assertTrue(alpha(color) < 230, "translucent " + hex(color));
        }
        assertTrue(brightness(deep) < brightness(thin));
        assertTrue(alpha(deep) > alpha(thin));
    }

    @Test
    void extremeInputsStayInRange() {
        float nan = Float.NaN;
        float inf = Float.POSITIVE_INFINITY;
        float[][] vectors = {
                {nan, nan, nan}, {inf, 0f, 0f}, {-inf, inf, 0f}, {0f, 0f, 0f}, {1e30f, -1e30f, 1e30f},
                {0f, 1f, 0f}, {0f, -1f, 0f}, {nan, 1f, 0f}};
        float[] thicknesses = {nan, -inf, -1f, 0f, 0.5f, 1f, 2f, inf};
        float[] flickers = {nan, -inf, -5f, 0f, 5f, inf};
        for (float[] n : vectors) {
            for (float[] v : vectors) {
                for (float thickness : thicknesses) {
                    int water = SmoothFluidRenderer.shadeWater(n[0], n[1], n[2], v[0], v[1], v[2], thickness);
                    int slime = SmoothFluidRenderer.shadeSlime(n[0], n[1], n[2], v[0], v[1], v[2], thickness);
                    assertTrue(alpha(water) >= THIN_WATER_ALPHA && alpha(water) <= HIGHLIGHT_ALPHA,
                            "water " + hex(water));
                    assertTrue(green(water) >= red(water) && brightness(water) > 100, "water " + hex(water));
                    assertTrue(alpha(slime) >= Math.round(0.62f * 255f) && alpha(slime) <= HIGHLIGHT_ALPHA,
                            "slime " + hex(slime));
                    assertTrue(green(slime) >= red(slime) && brightness(slime) > 100, "slime " + hex(slime));
                    for (float flicker : flickers) {
                        int lava = SmoothFluidRenderer.shadeLava(n[0], n[1], n[2], v[0], v[1], v[2], thickness,
                                flicker);
                        assertEquals(255, alpha(lava));
                        assertTrue(red(lava) > 40 && red(lava) >= green(lava) && green(lava) >= blue(lava),
                                "lava " + hex(lava));
                    }
                }
            }
        }
        double[] coordinates = {Double.NaN, -3e7, 0.0, 1e9, Double.POSITIVE_INFINITY};
        float[] times = {nan, 0f, 71_999.9f, inf};
        for (double c : coordinates) {
            for (float time : times) {
                float flicker = SmoothFluidRenderer.lavaFlicker(c, 64.0, c, time);
                assertTrue(flicker >= -1f && flicker <= 1f, "flicker " + flicker + " at " + c + ", " + time);
            }
        }
    }

    @Test
    void emitWritesEachSortedTriangleAsAQuadWithItsLastVertexDoubled() {
        MarchingCubesMesher mesh = meshOf(MATERIAL_WATER);
        int triangles = mesh.triangleCount();
        assertTrue(triangles > 0, "a block of water has a surface");
        Recorder sink = new Recorder();

        int written = new SmoothFluidRenderer().emit(mesh, DAY, sink, CAM_X, CAM_Y, CAM_Z, 0f);

        assertEquals(SmoothFluidRenderer.VERTICES_PER_TRIANGLE * triangles, written);
        assertEquals(written, sink.count());
        for (int t = 0; t < triangles; t++) {
            for (int k = 0; k < 3; k++) {
                int v = mesh.triangleVertex(t, k);
                int r = t * 4 + k;
                assertEquals(mesh.vertexX(v), sink.x.get(r), "x of corner " + k + " of triangle " + t);
                assertEquals(mesh.vertexY(v), sink.y.get(r));
                assertEquals(mesh.vertexZ(v), sink.z.get(r));
            }
            int third = t * 4 + 2;
            int fourth = t * 4 + 3;
            assertEquals(sink.x.get(third), sink.x.get(fourth), "the fourth vertex repeats the third");
            assertEquals(sink.y.get(third), sink.y.get(fourth));
            assertEquals(sink.z.get(third), sink.z.get(fourth));
            assertEquals(sink.colors.get(third), sink.colors.get(fourth));
            assertEquals(sink.lights.get(third), sink.lights.get(fourth));
        }
        for (int r = 0; r < written; r++) {
            int color = sink.colors.get(r);
            assertEquals(DAYLIGHT, sink.lights.get(r), "water takes the light of its block");
            assertTrue(alpha(color) >= THIN_WATER_ALPHA && alpha(color) <= HIGHLIGHT_ALPHA, hex(color));
        }
    }

    @Test
    void lavaOnTheSurfaceIsFullBrightAndOpaque() {
        MarchingCubesMesher mesh = meshOf(MATERIAL_LAVA);
        assertTrue(mesh.triangleCount() > 0);
        Recorder sink = new Recorder();

        int written = new SmoothFluidRenderer().emit(mesh, DAY, sink, CAM_X, CAM_Y, CAM_Z, 13f);

        assertEquals(sink.count(), written);
        for (int r = 0; r < written; r++) {
            assertEquals(ParticleMesher.FULL_BRIGHT, sink.lights.get(r));
            assertEquals(255, alpha(sink.colors.get(r)));
        }
    }

    @Test
    void emittingAllocatesNothingOnceGrown() {
        ParticleSoA particles = new ParticleSoA(1024);
        particles.setOrigin(ORIGIN_X, ORIGIN_Y, ORIGIN_Z);
        addBlock(particles, -2, 0, 0, MATERIAL_WATER);
        addBlock(particles, -1, 0, 0, MATERIAL_WATER);
        addBlock(particles, 0, 0, -1, MATERIAL_LAVA);
        addBlock(particles, 1, 0, 0, MATERIAL_SLIME);
        FluidDensityField field = new FluidDensityField();
        MarchingCubesMesher mesh = new MarchingCubesMesher();
        mesh.begin(CAM_X, CAM_Y, CAM_Z, 0f, FWD_Y, FWD_Z);
        for (byte material : new byte[] {MATERIAL_WATER, MATERIAL_LAVA, MATERIAL_SLIME}) {
            if (field.build(particles, 0f, material, null) > 0) {
                mesh.mesh(field, material, 0f);
            }
        }
        int triangles = mesh.sort();
        assertTrue(triangles > 0);
        SmoothFluidRenderer renderer = new SmoothFluidRenderer();
        VertexCounter sink = new VertexCounter();
        for (int i = 0; i < 50; i++) {
            renderer.emit(mesh, DAY, sink, CAM_X, CAM_Y, CAM_Z, i);
        }

        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        int written = 0;
        for (int i = 0; i < 20; i++) {
            written = renderer.emit(mesh, DAY, sink, CAM_X, CAM_Y, CAM_Z, i * 0.5f);
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertEquals(SmoothFluidRenderer.VERTICES_PER_TRIANGLE * triangles, written);
        assertEquals(70 * written, sink.vertices);
        assertEquals(0L, allocated, "emitting must not allocate");
    }

    /** A sorted mesh of one block of {@code material} (64 particles) in front of and below the camera. */
    private static MarchingCubesMesher meshOf(byte material) {
        ParticleSoA particles = new ParticleSoA(ParticleSoA.PARTICLES_PER_BLOCK);
        particles.setOrigin(ORIGIN_X, ORIGIN_Y, ORIGIN_Z);
        addBlock(particles, 0, 0, 0, material);
        FluidDensityField field = new FluidDensityField();
        assertTrue(field.build(particles, 0f, material, null) > 0);
        MarchingCubesMesher mesh = new MarchingCubesMesher();
        mesh.begin(CAM_X, CAM_Y, CAM_Z, 0f, FWD_Y, FWD_Z);
        mesh.mesh(field, material, 0f);
        mesh.sort();
        return mesh;
    }

    /** Fills the block at origin-relative ({@code bx}, {@code by}, {@code bz}) with 4 x 4 x 4 resting particles. */
    private static void addBlock(ParticleSoA particles, int bx, int by, int bz, byte material) {
        float step = ParticleSoA.PARTICLE_SPACING;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                for (int k = 0; k < 4; k++) {
                    particles.add(bx + (i + 0.5f) * step, by + (j + 0.5f) * step, bz + (k + 0.5f) * step,
                            0f, 0f, 0f, material);
                }
            }
        }
    }

    private static float[] normalize(float x, float y, float z) {
        float inv = 1f / (float) Math.sqrt(x * x + y * y + z * z);
        return new float[] {x * inv, y * inv, z * inv};
    }

    private static int brightness(int argb) {
        return red(argb) + green(argb) + blue(argb);
    }

    private static String hex(int argb) {
        return String.format("#%08X", argb);
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
        final List<Float> x = new ArrayList<>();
        final List<Float> y = new ArrayList<>();
        final List<Float> z = new ArrayList<>();
        final List<Integer> colors = new ArrayList<>();
        final List<Integer> lights = new ArrayList<>();

        @Override
        public void vertex(float vx, float vy, float vz, int argb, int packedLight) {
            x.add(vx);
            y.add(vy);
            z.add(vz);
            colors.add(argb);
            lights.add(packedLight);
        }

        int count() {
            return x.size();
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
