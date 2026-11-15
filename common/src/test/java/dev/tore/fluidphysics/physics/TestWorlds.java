// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

/** Hand-built voxel worlds for solver tests; no Minecraft level needed. */
final class TestWorlds {
    private TestWorlds() {
    }

    /**
     * An open box: a stone floor at y = 0 (its top surface is y = 1) under x in 0..width-1 and z in 0..depth-1,
     * enclosed by walls from y = 1 up to {@code wallHeight}.
     */
    static ChunkVoxelCache box(int width, int depth, int wallHeight) {
        ChunkVoxelCache cache = new ChunkVoxelCache();
        for (int x = -1; x <= width; x++) {
            for (int z = -1; z <= depth; z++) {
                cache.putMask(x, 0, z, ChunkVoxelCache.FULL);
                boolean wall = x == -1 || x == width || z == -1 || z == depth;
                if (wall) {
                    for (int y = 1; y <= wallHeight; y++) {
                        cache.putMask(x, y, z, ChunkVoxelCache.FULL);
                    }
                }
            }
        }
        return cache;
    }

    /** Fills the blocks x0..x1-1, y0..y1-1, z0..z1-1 with 64 particles each. */
    static void fill(ParticleSoA particles, byte material, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int y = y0; y < y1; y++) {
            for (int z = z0; z < z1; z++) {
                for (int x = x0; x < x1; x++) {
                    particles.spawnBlock(x, y, z, material, ParticleSoA.PARTICLES_PER_BLOCK, 0f, 0f, 0f);
                }
            }
        }
    }
}
