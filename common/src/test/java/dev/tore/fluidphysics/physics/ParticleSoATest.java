// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ParticleSoATest {
    @Test
    void removeMovesTheLastParticleIntoTheGap() {
        ParticleSoA particles = new ParticleSoA(8);
        particles.add(1f, 0f, 0f, 0f, 0f, 0f, ParticleSoA.MATERIAL_WATER);
        particles.add(2f, 0f, 0f, 0f, 0f, 0f, ParticleSoA.MATERIAL_LAVA);
        particles.add(3f, 0f, 0f, 0f, 0f, 0f, ParticleSoA.MATERIAL_SAND);

        particles.remove(0);

        assertEquals(2, particles.activeCount);
        assertEquals(3f, particles.posX[0]);
        assertEquals(ParticleSoA.MATERIAL_SAND, particles.material[0]);
        assertEquals(2f, particles.posX[1]);
    }

    @Test
    void addStopsAtTheRuntimeLimit() {
        ParticleSoA particles = new ParticleSoA(100);
        particles.setMaxParticles(70);

        int spawned = particles.spawnBlock(0, 0, 0, ParticleSoA.MATERIAL_WATER, 64, 0f, 0f, 0f)
                + particles.spawnBlock(1, 0, 0, ParticleSoA.MATERIAL_WATER, 64, 0f, 0f, 0f);

        assertEquals(70, spawned);
        assertEquals(-1, particles.add(0f, 0f, 0f, 0f, 0f, 0f, ParticleSoA.MATERIAL_WATER));
    }

    @Test
    void spawnBlockPutsOneParticleInEverySubVoxel() {
        ParticleSoA particles = new ParticleSoA(64);
        particles.setOrigin(100, 60, -200);

        particles.spawnBlock(103, 64, -198, ParticleSoA.MATERIAL_WATER, 64, 0f, 0f, 0f);

        boolean[] occupied = new boolean[64];
        for (int i = 0; i < particles.activeCount; i++) {
            double wx = particles.worldX(i) - 103;
            double wy = particles.worldY(i) - 64;
            double wz = particles.worldZ(i) - -198;
            assertTrue(wx > 0 && wx < 1 && wy > 0 && wy < 1 && wz > 0 && wz < 1, "particle outside its block");
            occupied[ChunkVoxelCache.subVoxelBit((int) (wx * 4), (int) (wy * 4), (int) (wz * 4))] = true;
        }
        for (boolean subVoxel : occupied) {
            assertTrue(subVoxel);
        }
    }

    @Test
    void rebaseKeepsWorldPositions() {
        ParticleSoA particles = new ParticleSoA(4);
        particles.setOrigin(1000, 64, 1000);
        particles.add(2.5f, 1.25f, -3.75f, 0f, 0f, 0f, ParticleSoA.MATERIAL_WATER);

        particles.rebase(1010, 60, 990);

        assertEquals(1002.5, particles.worldX(0), 1e-4);
        assertEquals(65.25, particles.worldY(0), 1e-4);
        assertEquals(996.25, particles.worldZ(0), 1e-4);
    }
}
