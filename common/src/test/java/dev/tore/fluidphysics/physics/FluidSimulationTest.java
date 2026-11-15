// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_LAVA;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_WATER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class FluidSimulationTest {
    private static final WorkerPool POOL = new WorkerPool(1);

    @AfterAll
    static void closePool() {
        POOL.close();
    }

    @Test
    void allocatesOnFirstSpawnAndRespectsTheLimit() {
        FluidSimulation sim = new FluidSimulation(POOL, 100);
        assertEquals(0, sim.capacity());
        assertNull(sim.particles());

        assertEquals(64, sim.spawn(0, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f));
        assertEquals(36, sim.spawn(1, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f));
        assertEquals(0, sim.spawn(2, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f));
        assertEquals(100, sim.activeCount());
        // The first allocation is capped by the limit, not the 4096 default.
        assertEquals(100, sim.capacity());
    }

    @Test
    void growsByDoublingAndKeepsParticles() {
        FluidSimulation sim = new FluidSimulation(POOL, ParticleSoA.MAX_PARTICLES);
        sim.spawn(5, 6, 7, MATERIAL_LAVA, 64, 0f, 0f, 0f);
        assertEquals(FluidSimulation.INITIAL_CAPACITY, sim.capacity());
        float x0 = sim.particles().posX[0];
        float y0 = sim.particles().posY[0];

        int blocks = FluidSimulation.INITIAL_CAPACITY / 64;
        for (int i = 0; i < blocks; i++) {
            sim.spawn(5 + i % 16, 7 + i / 256, 7 + (i / 16) % 16, MATERIAL_WATER, 64, 0f, 0f, 0f);
        }
        assertEquals(FluidSimulation.INITIAL_CAPACITY + 64, sim.activeCount());
        assertEquals(2 * FluidSimulation.INITIAL_CAPACITY, sim.capacity());
        assertEquals(x0, sim.particles().posX[0]);
        assertEquals(y0, sim.particles().posY[0]);
        assertEquals(MATERIAL_LAVA, sim.particles().material[0]);
        assertEquals(5, sim.particles().originX);
    }

    @Test
    void loweringTheLimitKeepsParticlesButStopsSpawns() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(0, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        sim.spawn(1, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);

        sim.setLimit(100);
        assertEquals(128, sim.activeCount());
        assertEquals(0, sim.spawn(2, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f));

        sim.setLimit(200);
        assertEquals(64, sim.spawn(2, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f));
    }

    @Test
    void rejectsBadInputFromTheNetwork() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        assertEquals(0, sim.spawn(0, 0, 0, (byte) 4, 64, 0f, 0f, 0f));
        assertEquals(0, sim.spawn(0, 0, 0, (byte) -1, 64, 0f, 0f, 0f));
        assertEquals(0, sim.spawn(0, 0, 0, MATERIAL_WATER, -5, 0f, 0f, 0f));
        assertEquals(64, sim.spawn(0, 0, 0, MATERIAL_WATER, 1000, Float.NaN, Float.POSITIVE_INFINITY, 1f));
        assertEquals(0f, sim.particles().velX[0]);
        assertEquals(0f, sim.particles().velY[0]);
        assertEquals(1f, sim.particles().velZ[0]);
        assertEquals(0, sim.applyImpulse(0.5, 0.5, 0.5, Float.NaN, 5f));
        assertEquals(0, sim.applyImpulse(0.5, 0.5, 0.5, 2f, Float.NaN));
        assertEquals(0, sim.removeNear(0, 0, 0, (byte) 9, 64));
        assertEquals(0, sim.removeNear(0, 0, 0, MATERIAL_WATER, -1));
    }

    @Test
    void refusesSpawnsTooFarFromTheOriginUntilCleared() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(100, 64, 100, MATERIAL_WATER, 64, 0f, 0f, 0f);
        int far = 100 + FluidSimulation.MAX_ORIGIN_DISTANCE + 1;
        assertEquals(0, sim.spawn(far, 64, 100, MATERIAL_WATER, 64, 0f, 0f, 0f));

        assertEquals(64, sim.clear());
        assertNull(sim.particles());
        assertEquals(64, sim.spawn(far, 64, 100, MATERIAL_WATER, 64, 0f, 0f, 0f));
        assertEquals(far, sim.particles().originX);
    }

    @Test
    void impulsePushesOutwardWithinTheRadiusAndWakesParticles() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(10, 20, 30, MATERIAL_WATER, 64, 0f, 0f, 0f);
        sim.spawn(20, 20, 30, MATERIAL_WATER, 64, 0f, 0f, 0f);
        ParticleSoA p = sim.particles();
        for (int i = 0; i < p.activeCount; i++) {
            p.sleepTicks[i] = 100;
        }

        // Centre of block (10, 20, 30): reaches all of that block, nothing of the one 10 blocks away.
        double cx = 10.5;
        double cy = 20.5;
        double cz = 30.5;
        assertEquals(64, sim.applyImpulse(cx, cy, cz, 2f, 5f));
        for (int i = 0; i < p.activeCount; i++) {
            double dx = p.worldX(i) - cx;
            double dy = p.worldY(i) - cy;
            double dz = p.worldZ(i) - cz;
            double outward = p.velX[i] * dx + p.velY[i] * dy + p.velZ[i] * dz;
            if (p.worldX(i) < 15) {
                assertTrue(outward > 0, "particle " + i + " not pushed outward");
                assertEquals(0, p.sleepTicks[i]);
            } else {
                assertEquals(0f, p.velX[i]);
                assertEquals(100, p.sleepTicks[i]);
            }
        }
    }

    @Test
    void condenseTakesTheBlockFirstThenItsNeighbours() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(0, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        sim.spawn(1, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        sim.spawn(0, 0, 0, MATERIAL_LAVA, 10, 0f, 0f, 0f);

        assertEquals(60, sim.removeNear(0, 0, 0, MATERIAL_WATER, 60));
        assertEquals(4, countInBlock(sim, 0, 0, 0, MATERIAL_WATER));
        assertEquals(64, countInBlock(sim, 1, 0, 0, MATERIAL_WATER));
        assertEquals(10, countInBlock(sim, 0, 0, 0, MATERIAL_LAVA));

        // Only 4 left in the block: the other 6 come from the neighbour.
        assertEquals(10, sim.removeNear(0, 0, 0, MATERIAL_WATER, 10));
        assertEquals(0, countInBlock(sim, 0, 0, 0, MATERIAL_WATER));
        assertEquals(58, countInBlock(sim, 1, 0, 0, MATERIAL_WATER));
        assertEquals(68, sim.activeCount());
    }

    @Test
    void particlesFallingOutOfTheWorldAreRemoved() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(0, -60, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        int voidY = -64 - FluidSimulation.VOID_DEPTH;
        // Free fall of about 20 blocks, capped at the solver's speed limit: well under 100 ticks.
        for (int t = 0; t < 100 && sim.activeCount() > 0; t++) {
            sim.advance(voidY);
        }
        assertEquals(0, sim.activeCount());
        assertEquals(64, sim.removedInVoid());
    }

    @Test
    void tickStatisticsAndCounts() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.voxels().putMask(0, -1, 0, ChunkVoxelCache.FULL);
        sim.spawn(0, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        sim.spawn(0, 1, 0, MATERIAL_LAVA, 32, 0f, 0f, 0f);
        for (int t = 0; t < 5; t++) {
            sim.advance(-1000);
        }
        assertTrue(sim.lastStepNanos() > 0);
        assertTrue(sim.averageStepNanos() > 0);
        assertTrue(sim.maxStepNanos() >= sim.averageStepNanos());
        int[] counts = new int[ParticleSoA.MATERIAL_COUNT];
        sim.countByMaterial(counts);
        assertEquals(64, counts[MATERIAL_WATER]);
        assertEquals(32, counts[MATERIAL_LAVA]);
        assertEquals(0, sim.countResting());
    }

    @Test
    void tickImpulseAndCondenseAllocateNothing() {
        FluidSimulation sim = new FluidSimulation(POOL, 4096);
        sim.voxels().putMask(0, -1, 0, ChunkVoxelCache.FULL);
        for (int i = 0; i < 16; i++) {
            sim.spawn(i % 4, i / 4, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        }
        for (int t = 0; t < 50; t++) {
            sim.advance(-1000);
        }

        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int t = 0; t < 50; t++) {
            sim.advance(-1000);
            sim.applyImpulse(2.0, 1.0, 0.5, 1.5f, 2f);
        }
        sim.removeNear(1, 0, 0, MATERIAL_WATER, 32);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertEquals(0L, allocated, "tick, impulse and condense must not allocate");
    }

    private static int countInBlock(FluidSimulation sim, int bx, int by, int bz, byte material) {
        ParticleSoA p = sim.particles();
        int n = 0;
        for (int i = 0; i < p.activeCount; i++) {
            if (p.material[i] == material && (int) Math.floor(p.worldX(i)) == bx
                    && (int) Math.floor(p.worldY(i)) == by && (int) Math.floor(p.worldZ(i)) == bz) {
                n++;
            }
        }
        return n;
    }
}
