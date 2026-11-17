// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.client.render;

import static dev.tore.fluidphysics.client.render.FluidDensityField.TILE_CELLS;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_LAVA;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_WATER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tore.fluidphysics.physics.AllocationMeter;
import dev.tore.fluidphysics.physics.ChunkVoxelCache;
import dev.tore.fluidphysics.physics.ParticleSoA;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FluidDensityFieldTest {
    private static final float ISO = MarchingCubesMesher.ISO_LEVEL;
    /** First and last local node a tile stores, per axis. */
    private static final int FIRST_NODE = -1;
    private static final int LAST_NODE = TILE_CELLS + 1;

    @Test
    void nodeValuesEqualABruteForceKernelSum() {
        ParticleSoA particles = new ParticleSoA(64);
        particles.setOrigin(5, -10, 300);
        Random random = new Random(3);
        // Around the tile corner at block (2, 2, 2), moving, so the interpolated position is the one that counts.
        for (int i = 0; i < 30; i++) {
            addMoving(particles, 2f + (random.nextFloat() - 0.5f) * 1.6f, 2f + (random.nextFloat() - 0.5f) * 1.6f,
                    2f + (random.nextFloat() - 0.5f) * 1.6f, random, MATERIAL_WATER);
        }
        // Right on and next to tile borders (multiples of 2 blocks).
        particles.add(2f, 1f, 1f, 0f, 0f, 0f, MATERIAL_WATER);
        particles.add(1.99f, 2.01f, 0.5f, 0f, 0f, 0f, MATERIAL_WATER);
        particles.add(-0.01f, 0.3f, 2f, 0f, 0f, 0f, MATERIAL_WATER);
        particles.add(4.0f, 4.0f, 4.0f, 0f, 0f, 0f, MATERIAL_WATER);
        // Another material, which must not show up in the water field.
        particles.add(2.1f, 2.1f, 2.1f, 0f, 0f, 0f, MATERIAL_LAVA);
        float partialTick = 0.4f;
        FluidDensityField field = new FluidDensityField();

        int tiles = field.build(particles, partialTick, MATERIAL_WATER, null);

        assertTrue(tiles > 8, "particles around a tile corner need its 8 tiles and more, got " + tiles);
        assertEquals(tiles, field.tileCount());
        assertFalse(field.overflowed());
        int nonZero = 0;
        for (int slot = 0; slot < tiles; slot++) {
            for (int lz = FIRST_NODE; lz <= LAST_NODE; lz++) {
                for (int ly = FIRST_NODE; ly <= LAST_NODE; ly++) {
                    for (int lx = FIRST_NODE; lx <= LAST_NODE; lx++) {
                        double expected = bruteForce(particles, partialTick, MATERIAL_WATER,
                                field.tileX(slot) * TILE_CELLS + lx, field.tileY(slot) * TILE_CELLS + ly,
                                field.tileZ(slot) * TILE_CELLS + lz);
                        float actual = field.nodeValue(slot, lx, ly, lz);
                        if (Math.abs(expected - actual) > 2e-5 * Math.max(1.0, expected)) {
                            throw new AssertionError("tile " + slot + " node (" + lx + ", " + ly + ", " + lz
                                    + "): expected " + expected + ", got " + actual);
                        }
                        if (actual > 0f) {
                            nonZero++;
                        }
                    }
                }
            }
        }
        assertTrue(nonZero > 1000, "only " + nonZero + " non-zero nodes");
        for (int i = 0; i < particles.activeCount; i++) {
            assertFalse(field.isSkipped(i));
        }
    }

    @Test
    void nodesSharedByNeighbouringTilesAreBitIdentical() {
        ParticleSoA particles = new ParticleSoA(512);
        particles.setOrigin(-100, 60, 40);
        Random random = new Random(17);
        for (int i = 0; i < 400; i++) {
            addMoving(particles, 2f + (random.nextFloat() - 0.5f) * 2.4f, 2f + (random.nextFloat() - 0.5f) * 2.4f,
                    2f + (random.nextFloat() - 0.5f) * 2.4f, random, MATERIAL_WATER);
        }
        FluidDensityField field = new FluidDensityField();

        int tiles = field.build(particles, 0.7f, MATERIAL_WATER, null);

        int pairs = 0;
        int sharedNonZero = 0;
        for (int a = 0; a < tiles; a++) {
            for (int b = a + 1; b < tiles; b++) {
                int ax = field.tileX(a) * TILE_CELLS;
                int ay = field.tileY(a) * TILE_CELLS;
                int az = field.tileZ(a) * TILE_CELLS;
                int bx = field.tileX(b) * TILE_CELLS;
                int by = field.tileY(b) * TILE_CELLS;
                int bz = field.tileZ(b) * TILE_CELLS;
                int x0 = Math.max(ax, bx) + FIRST_NODE;
                int x1 = Math.min(ax, bx) + LAST_NODE;
                int y0 = Math.max(ay, by) + FIRST_NODE;
                int y1 = Math.min(ay, by) + LAST_NODE;
                int z0 = Math.max(az, bz) + FIRST_NODE;
                int z1 = Math.min(az, bz) + LAST_NODE;
                if (x0 > x1 || y0 > y1 || z0 > z1) {
                    continue;
                }
                pairs++;
                for (int gz = z0; gz <= z1; gz++) {
                    for (int gy = y0; gy <= y1; gy++) {
                        for (int gx = x0; gx <= x1; gx++) {
                            float va = field.nodeValue(a, gx - ax, gy - ay, gz - az);
                            float vb = field.nodeValue(b, gx - bx, gy - by, gz - bz);
                            if (Float.floatToRawIntBits(va) != Float.floatToRawIntBits(vb)) {
                                throw new AssertionError("node (" + gx + ", " + gy + ", " + gz + ") is " + va
                                        + " in tile " + a + " but " + vb + " in tile " + b);
                            }
                            if (va > 0f) {
                                sharedNonZero++;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(pairs >= 26, "only " + pairs + " neighbouring tile pairs");
        assertTrue(sharedNonZero > 1000, "only " + sharedNonZero + " non-zero shared nodes");
    }

    @Test
    void wettingMakesARestingLayerFlatterAndWider() {
        // A 4 x 4 layer of particles resting on a stone floor whose top is at origin-relative y = 0.
        ParticleSoA particles = new ParticleSoA(16);
        particles.setOrigin(10, 70, -5);
        for (int iz = 0; iz < 4; iz++) {
            for (int ix = 0; ix < 4; ix++) {
                particles.add(0.125f + 0.25f * ix, 0.125f, 0.125f + 0.25f * iz, 0f, 0f, 0f, MATERIAL_WATER);
            }
        }
        ChunkVoxelCache voxels = new ChunkVoxelCache();
        for (int x = -2; x <= 3; x++) {
            for (int z = -2; z <= 3; z++) {
                voxels.putMask(10 + x, 69, -5 + z, ChunkVoxelCache.FULL);
            }
        }
        FluidDensityField dry = new FluidDensityField();
        FluidDensityField wet = new FluidDensityField();

        dry.build(particles, 0f, MATERIAL_WATER, null);
        wet.build(particles, 0f, MATERIAL_WATER, voxels);

        // Surface height above the centre of the layer (block x = z = 0.5 is node 4), and its edge along +x at the
        // particles' height (y = 0.125 is node 1).
        float dryTop = surfaceAlongY(dry, 4, 1, 4);
        float wetTop = surfaceAlongY(wet, 4, 1, 4);
        float dryEdge = surfaceAlongX(dry, 4, 1, 4);
        float wetEdge = surfaceAlongX(wet, 4, 1, 4);
        assertTrue(wetTop < dryTop - 0.01f, "wet top " + wetTop + " should be below dry top " + dryTop);
        assertTrue(wetEdge > dryEdge + 0.03f, "wet edge " + wetEdge + " should reach past dry edge " + dryEdge);

        // The same layer one block up in the air does not touch the floor and stays round.
        for (int i = 0; i < particles.activeCount; i++) {
            particles.posY[i] += 1f;
            particles.prevY[i] += 1f;
        }
        wet.build(particles, 0f, MATERIAL_WATER, voxels);
        assertEquals(dryTop + 1f, surfaceAlongY(wet, 4, 9, 4), 1e-4f);
    }

    @Test
    void particlesWhoseTilesDoNotFitAreSkippedAndLeaveNoTrace() {
        // One particle in the middle of every other tile: each needs exactly one tile, no two share or touch one.
        int side = 11; // 1331 particles > MAX_TILES
        ParticleSoA particles = new ParticleSoA(side * side * side + 2);
        particles.setOrigin(1000, 0, -1000);
        for (int a = 0; a < side; a++) {
            for (int b = 0; b < side; b++) {
                for (int c = 0; c < side; c++) {
                    particles.add(4f * a + 1f, 4f * b + 1f, 4f * c + 1f, 0f, 0f, 0f, MATERIAL_WATER);
                }
            }
        }
        // Next to the first particle's tile but reaching into the missing tile (1, 0, 0): skipped, although its
        // kernel overlaps the stored nodes of tile (0, 0, 0).
        int straddling = particles.add(1.95f, 1f, 1f, 0f, 0f, 0f, MATERIAL_WATER);
        // Entirely inside tile (0, 0, 0), which exists: fits without a new tile.
        int inside = particles.add(1.1f, 1f, 1f, 0f, 0f, 0f, MATERIAL_WATER);
        FluidDensityField field = new FluidDensityField();

        int tiles = field.build(particles, 0f, MATERIAL_WATER, null);

        assertEquals(FluidDensityField.MAX_TILES, tiles);
        assertTrue(field.overflowed());
        for (int i = 0; i < side * side * side; i++) {
            assertEquals(i >= FluidDensityField.MAX_TILES, field.isSkipped(i), "particle " + i);
            int tile = field.findTile(Math.floorDiv(i / (side * side) * 4 + 1, 2),
                    Math.floorDiv(i / side % side * 4 + 1, 2), Math.floorDiv(i % side * 4 + 1, 2));
            assertEquals(i < FluidDensityField.MAX_TILES, tile >= 0, "tile of particle " + i);
        }
        assertTrue(field.isSkipped(straddling));
        assertFalse(field.isSkipped(inside));
        assertEquals(side * side * side - FluidDensityField.MAX_TILES + 1, field.skippedCount());

        // Tile (0, 0, 0) holds exactly particle 0 and the one inside it: nothing of the skipped neighbour.
        int first = field.findTile(0, 0, 0);
        for (int lz = FIRST_NODE; lz <= LAST_NODE; lz++) {
            for (int ly = FIRST_NODE; ly <= LAST_NODE; ly++) {
                for (int lx = FIRST_NODE; lx <= LAST_NODE; lx++) {
                    // Tile (0, 0, 0): global node = local node.
                    double expected = kernelAt(particles.posX[0], particles.posY[0], particles.posZ[0], lx, ly, lz)
                            + kernelAt(particles.posX[inside], particles.posY[inside], particles.posZ[inside],
                            lx, ly, lz);
                    assertEquals(expected, field.nodeValue(first, lx, ly, lz), 2e-5);
                }
            }
        }
        // Every other stored tile holds just its own particle.
        for (int slot = 0; slot < tiles; slot += 37) {
            if (slot == first) {
                continue;
            }
            float cx = field.tileX(slot) * 2f + 1f;
            float cy = field.tileY(slot) * 2f + 1f;
            float cz = field.tileZ(slot) * 2f + 1f;
            // Along x through the tile's middle node row (local y = z = 8 is the particle's height and depth).
            int gy = field.tileY(slot) * TILE_CELLS + 8;
            int gz = field.tileZ(slot) * TILE_CELLS + 8;
            for (int l = FIRST_NODE; l <= LAST_NODE; l++) {
                int gx = field.tileX(slot) * TILE_CELLS + l;
                assertEquals(kernelAt(cx, cy, cz, gx, gy, gz), field.nodeValue(slot, l, 8, 8), 2e-5);
            }
        }
    }

    @Test
    void aMaterialWithoutParticlesHasNoTiles() {
        ParticleSoA particles = new ParticleSoA(8);
        particles.add(0.5f, 0.5f, 0.5f, 0f, 0f, 0f, MATERIAL_WATER);
        FluidDensityField field = new FluidDensityField();
        assertTrue(field.build(particles, 0f, MATERIAL_WATER, null) > 0);

        assertEquals(0, field.build(particles, 0f, MATERIAL_LAVA, null));
        assertEquals(0, field.tileCount());
        assertFalse(field.overflowed());
        assertFalse(field.isSkipped(0));

        assertEquals(0, field.build(new ParticleSoA(1), 0f, MATERIAL_WATER, null), "no particles at all");
    }

    @Test
    void aPackedBlockReadsFullThicknessAndADropletThin() {
        ParticleSoA particles = new ParticleSoA(64 * 27 + 1);
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) {
                    particles.spawnBlock(x, y, z, MATERIAL_WATER, ParticleSoA.PARTICLES_PER_BLOCK, 0f, 0f, 0f);
                }
            }
        }
        particles.add(10.3f, 0.4f, 0.6f, 0f, 0f, 0f, MATERIAL_WATER);
        FluidDensityField field = new FluidDensityField();
        field.build(particles, 0f, MATERIAL_WATER, null);
        float n = FluidDensityField.CELLS_PER_BLOCK;

        // The middle of block (1, 1, 1) in global fine-node units lies in tile (0, 0, 0).
        float packed = field.thickness(field.findTile(0, 0, 0), 1.5f * n, 1.5f * n, 1.5f * n);
        float droplet = field.thickness(field.findTile(5, 0, 0), 10.3f * n, 0.4f * n, 0.6f * n);

        assertTrue(packed > 0.9f, "packed thickness " + packed);
        assertTrue(droplet > 0f && droplet < 0.2f, "droplet thickness " + droplet);
    }

    @Test
    void fastParticlesStirTheImpactField() {
        ParticleSoA particles = new ParticleSoA(8);
        particles.add(0.5f, 0.5f, 0.5f, 0f, 0f, 0f, MATERIAL_WATER);
        particles.add(0.75f, 0.5f, 0.5f, 0f, 0.5f, 0f, MATERIAL_WATER);
        FluidDensityField field = new FluidDensityField();
        field.build(particles, 0f, MATERIAL_WATER, null);
        int slot = field.findTile(0, 0, 0);
        assertEquals(0f, field.impact(slot, 4f, 4f, 4f), "slow particles leave the surface calm");

        assertEquals(FluidDensityField.FAR_SOURCE, field.sourceDistance(slot, 4f, 4f, 4f), "no source anywhere");

        particles.velY[1] = -8f;
        field.build(particles, 0f, MATERIAL_WATER, null);
        slot = field.findTile(0, 0, 0);
        // At the particle (block 0.75, 0.5, 0.5 = fine node 6, 4, 4) and 0.75 blocks above it.
        float centre = field.impact(slot, 6f, 4f, 4f);
        float away = field.impact(slot, 6f, 4f + 6f, 4f);
        assertTrue(centre > 0.5f, "impact under a falling particle " + centre);
        assertTrue(away < centre, "impact fades with distance: " + away + " vs " + centre);
        // A single fast particle is a source of its own: the coarse nodes around it read their distance to it.
        float near = field.sourceDistance(slot, 6f, 4f, 4f);
        float far = field.sourceDistance(slot, 6f, 4f + 6f, 4f);
        assertEquals(0.25f, near, 1e-5f, "the nearest coarse nodes are a quarter block from the particle");
        assertTrue(far > near + 0.3f && far <= FluidDensityField.FAR_SOURCE, "source distance " + far);

        // A second fast particle in the same block joins the source: twice the amount, at the centroid.
        particles.add(0.75f, 0.5f, 0.5f, 0f, -8f, 0f, MATERIAL_WATER);
        field.build(particles, 0f, MATERIAL_WATER, null);
        slot = field.findTile(0, 0, 0);
        assertEquals(2f * centre, field.impact(slot, 6f, 4f, 4f), 1e-5f);
    }

    @Test
    void buildingAllocatesNothingAfterWarmUp() {
        Random random = new Random(23);
        ParticleSoA particles = new ParticleSoA(8192);
        particles.setOrigin(64, 64, 64);
        for (int i = 0; i < particles.capacity; i++) {
            addMoving(particles, random.nextFloat() * 12f - 6f, random.nextFloat() * 3f,
                    random.nextFloat() * 12f - 6f, random, (byte) (i % 3 == 0 ? MATERIAL_LAVA : MATERIAL_WATER));
        }
        ChunkVoxelCache voxels = new ChunkVoxelCache();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                voxels.putMask(64 + x, 63, 64 + z, ChunkVoxelCache.FULL);
            }
        }
        FluidDensityField field = new FluidDensityField();
        int frames = 20;
        for (int warmUp = 0; warmUp < 3; warmUp++) {
            for (int i = 0; i < frames; i++) {
                field.build(particles, i / (float) frames, MATERIAL_WATER, voxels);
                field.build(particles, i / (float) frames, MATERIAL_LAVA, voxels);
            }
        }

        long before = AllocationMeter.currentThreadBytes();
        long start = System.nanoTime();
        for (int i = 0; i < frames; i++) {
            field.build(particles, i / (float) frames, MATERIAL_WATER, voxels);
            field.build(particles, i / (float) frames, MATERIAL_LAVA, voxels);
        }
        double msPerFrame = (System.nanoTime() - start) / (double) frames / 1.0e6;
        long allocated = AllocationMeter.currentThreadBytes() - before;
        System.out.printf("FluidDensityField @ %d particles: %.2f ms per frame (two materials), %d lava tiles, "
                + "%d bytes allocated in %d frames%n", particles.activeCount, msPerFrame, field.tileCount(),
                allocated, frames);

        assertEquals(0L, allocated, "building the field must not allocate");
    }

    // ---- helpers ----

    /** Adds a particle that moved a little during the last tick. */
    private static void addMoving(ParticleSoA particles, float x, float y, float z, Random random, byte material) {
        float vx = (random.nextFloat() - 0.5f) * 4f;
        float vy = (random.nextFloat() - 0.5f) * 4f;
        float vz = (random.nextFloat() - 0.5f) * 4f;
        int i = particles.add(x, y, z, vx, vy, vz, material);
        particles.prevX[i] = x - vx * 0.05f;
        particles.prevY[i] = y - vy * 0.05f;
        particles.prevZ[i] = z - vz * 0.05f;
    }

    /** The field value at a global node from scratch: the round kernel of every particle of the material. */
    private static double bruteForce(ParticleSoA particles, float partialTick, byte material, int gx, int gy, int gz) {
        double sum = 0;
        for (int i = 0; i < particles.activeCount; i++) {
            if (particles.material[i] != material) {
                continue;
            }
            float x = particles.prevX[i] + (particles.posX[i] - particles.prevX[i]) * partialTick;
            float y = particles.prevY[i] + (particles.posY[i] - particles.prevY[i]) * partialTick;
            float z = particles.prevZ[i] + (particles.posZ[i] - particles.prevZ[i]) * partialTick;
            sum += kernelAt(x, y, z, gx, gy, gz);
        }
        return sum;
    }

    /** Round kernel of a particle at origin-relative ({@code x}, {@code y}, {@code z}) at a global node. */
    private static double kernelAt(float x, float y, float z, int gx, int gy, int gz) {
        double r = FluidDensityField.KERNEL_RADIUS;
        double dx = gx * (double) FluidDensityField.CELL_SIZE - x;
        double dy = gy * (double) FluidDensityField.CELL_SIZE - y;
        double dz = gz * (double) FluidDensityField.CELL_SIZE - z;
        double q = (dx * dx + dy * dy + dz * dz) / (r * r);
        return q < 1 ? (1 - q) * (1 - q) * (1 - q) : 0;
    }

    /**
     * Height (origin-relative blocks) where the field drops to the iso level going up from global node
     * ({@code gx}, {@code gy}, {@code gz}), linearly interpolated between nodes. The column must lie in one tile.
     */
    private static float surfaceAlongY(FluidDensityField field, int gx, int gy, int gz) {
        int slot = field.findTile(Math.floorDiv(gx, TILE_CELLS), Math.floorDiv(gy, TILE_CELLS),
                Math.floorDiv(gz, TILE_CELLS));
        int ox = Math.floorDiv(gx, TILE_CELLS) * TILE_CELLS;
        int oy = Math.floorDiv(gy, TILE_CELLS) * TILE_CELLS;
        int oz = Math.floorDiv(gz, TILE_CELLS) * TILE_CELLS;
        float below = field.nodeValue(slot, gx - ox, gy - oy, gz - oz);
        assertTrue(below > ISO, "start node is not inside");
        for (int y = gy + 1; y - oy <= LAST_NODE; y++) {
            float value = field.nodeValue(slot, gx - ox, y - oy, gz - oz);
            if (value <= ISO) {
                return (y - 1 + (below - ISO) / (below - value)) * FluidDensityField.CELL_SIZE;
            }
            below = value;
        }
        throw new AssertionError("no surface above node " + gy);
    }

    /** Like {@link #surfaceAlongY}, going along +x. */
    private static float surfaceAlongX(FluidDensityField field, int gx, int gy, int gz) {
        int slot = field.findTile(Math.floorDiv(gx, TILE_CELLS), Math.floorDiv(gy, TILE_CELLS),
                Math.floorDiv(gz, TILE_CELLS));
        int ox = Math.floorDiv(gx, TILE_CELLS) * TILE_CELLS;
        int oy = Math.floorDiv(gy, TILE_CELLS) * TILE_CELLS;
        int oz = Math.floorDiv(gz, TILE_CELLS) * TILE_CELLS;
        float before = field.nodeValue(slot, gx - ox, gy - oy, gz - oz);
        assertTrue(before > ISO, "start node is not inside");
        for (int x = gx + 1; x - ox <= LAST_NODE; x++) {
            float value = field.nodeValue(slot, x - ox, gy - oy, gz - oz);
            if (value <= ISO) {
                return (x - 1 + (before - ISO) / (before - value)) * FluidDensityField.CELL_SIZE;
            }
            before = value;
        }
        throw new AssertionError("no surface along x from node " + gx);
    }
}
