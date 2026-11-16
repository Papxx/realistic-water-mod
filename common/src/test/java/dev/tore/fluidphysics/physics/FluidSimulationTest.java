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
        SimulationStats stats = sim.stats();
        assertEquals(5, stats.samples());
        assertTrue(stats.step().last() > 0);
        assertTrue(stats.step().mean() > 0);
        assertTrue(stats.step().max() >= stats.step().mean());
        assertTrue(stats.tick().last() >= stats.step().last(), "the tick includes the solver step");
        long phases = 0L;
        for (int phase = 0; phase < PbfSolver.PHASE_COUNT; phase++) {
            phases += stats.phase(phase).last();
        }
        assertEquals(stats.step().last(), phases, "the solver phases add up to the step");
        assertTrue(stats.lastMeanNeighbors() > 1f && stats.lastMaxNeighbors() >= stats.lastMeanNeighbors());

        int[] counts = new int[ParticleSoA.MATERIAL_COUNT];
        sim.countByMaterial(counts);
        assertEquals(64, counts[MATERIAL_WATER]);
        assertEquals(32, counts[MATERIAL_LAVA]);
        assertEquals(0, sim.countResting());

        // Ticks without particles are not recorded, and clear() forgets the samples.
        sim.clear();
        sim.advance(-1000);
        assertEquals(0, sim.stats().samples());
    }

    @Test
    void tickImpulseAndCondenseAllocateNothing() {
        FluidSimulation sim = new FluidSimulation(POOL, 4096);
        sim.voxels().putMask(0, -1, 0, ChunkVoxelCache.FULL);
        for (int i = 0; i < 16; i++) {
            sim.spawn(i % 4, i / 4, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        }
        for (int t = 0; t < 50; t++) {
            sim.wakeAround(t % 4, 0, 0);
            sim.advance(-1000);
        }

        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int t = 0; t < 50; t++) {
            sim.wakeAround(t % 4, 0, 0);
            sim.advance(-1000);
            sim.applyImpulse(2.0, 1.0, 0.5, 1.5f, 2f);
        }
        sim.removeNear(1, 0, 0, MATERIAL_WATER, 32);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertEquals(0L, allocated, "tick, wake-up, impulse and condense must not allocate");
    }

    @Test
    void occupancyCountsParticlesPerBlockAndMaterial() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        assertNull(sim.occupancy());
        sim.spawn(3, 4, 5, MATERIAL_WATER, 64, 2f, 0f, 0f);
        sim.spawn(3, 4, 5, MATERIAL_LAVA, 10, 0f, 0f, 0f);
        sim.spawn(-7, 4, 5, MATERIAL_WATER, 20, 0f, 0f, 0f);
        ParticleSoA p = sim.particles();
        for (int i = 0; i < 30; i++) {
            p.sleepTicks[i] = FluidSimulation.RESTING_TICKS + 1;
        }

        BlockOccupancy occupancy = sim.occupancy();
        assertEquals(2, occupancy.size());
        int slot = occupancy.slotOf(3, 4, 5);
        assertEquals(64, occupancy.count(slot, MATERIAL_WATER));
        assertEquals(10, occupancy.count(slot, MATERIAL_LAVA));
        assertEquals(74, occupancy.total(slot));
        assertEquals(30, occupancy.resting(slot, MATERIAL_WATER));
        assertEquals(64 * 2f / 74, occupancy.meanVelX(slot), 1e-5f);
        assertEquals(20, occupancy.count(-7, 4, 5, MATERIAL_WATER));
        assertEquals(-1, occupancy.slotOf(0, 0, 0));
        assertEquals(0, occupancy.count(0, 0, 0, MATERIAL_WATER));
        // Unchanged particles: the same table, not rebuilt.
        assertTrue(occupancy == sim.occupancy());
    }

    @Test
    void markNearestPicksTheClosestAndRemoveMarkedReportsThem() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        for (int x = 0; x < 4; x++) {
            sim.spawn(x, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        }
        sim.spawn(0, 0, 0, MATERIAL_LAVA, 64, 0f, 0f, 0f);

        // 64 nearest to a point a quarter block into block 0: exactly the water of block 0 (its farthest particle is
        // 0.82 away, the nearest of block 1 is 0.89 away; lava is another material). From the block centre, the
        // corners of block 0 and the face of block 1 would be equally far.
        assertEquals(64, sim.markNearest(0.25, 0.5, 0.5, MATERIAL_WATER, 64, 10f, false, true));
        int[] removedPerBlock = new int[4];
        assertEquals(64, sim.removeMarked((x, y, z, material) -> {
            assertEquals(MATERIAL_WATER, material);
            removedPerBlock[x]++;
        }));
        assertEquals(64, removedPerBlock[0]);
        assertEquals(0, countInBlock(sim, 0, 0, 0, MATERIAL_WATER));
        assertEquals(64, countInBlock(sim, 1, 0, 0, MATERIAL_WATER));
        assertEquals(64, countInBlock(sim, 0, 0, 0, MATERIAL_LAVA));
        for (int i = 0; i < sim.activeCount(); i++) {
            assertTrue(!sim.isMarked(i), "marks must be cleared after removal");
        }

        // All or nothing: 1 block radius around block 3 holds only 64 + some of block 2, not 300.
        assertEquals(0, sim.markNearest(3.5, 0.5, 0.5, MATERIAL_WATER, 300, 1f, false, true));
        assertEquals(0, sim.removeMarked(null));
        // Resting only: nothing rests yet.
        assertEquals(0, sim.markNearest(3.5, 0.5, 0.5, MATERIAL_WATER, 10, 5f, true, false));
        assertEquals(5, sim.markInBlock(2, 0, 0, MATERIAL_WATER, 5));
        assertEquals(5, sim.removeMarked(null));
        assertEquals(59, countInBlock(sim, 2, 0, 0, MATERIAL_WATER));
    }

    @Test
    void markingAndRemovingAllocateNothing() {
        FluidSimulation sim = new FluidSimulation(POOL, 4096);
        for (int i = 0; i < 32; i++) {
            sim.spawn(i % 8, i / 8, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        }
        // Warm-up: the first rounds allocate the tables and, once, a few hundred bytes while the JIT settles.
        for (int round = 0; round < 5; round++) {
            sim.markNearest(4.0, 2.0, 0.5, MATERIAL_WATER, 64, 3f, false, true);
            sim.markInBlock(1, 1, 0, MATERIAL_WATER, 10);
            sim.removeMarked(null);
            sim.occupancy();
        }

        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int round = 0; round < 10; round++) {
            sim.markNearest(4.0, 2.0, 0.5, MATERIAL_WATER, 64, 3f, false, true);
            sim.markInBlock(1, 1, 0, MATERIAL_WATER, 10);
            sim.removeMarked(null);
            sim.occupancy();
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertEquals(0L, allocated, "marking, removing and counting must not allocate");
    }

    @Test
    void blockChangesWakeTheParticlesAroundThemAtTheNextTick() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(0, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        sim.spawn(1, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        sim.spawn(3, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        ParticleSoA p = sim.particles();
        java.util.Arrays.fill(p.sleepTicks, 0, p.activeCount, (short) 500);

        // The floor under block 0 is removed: blocks 0 and 1 are within the 3 x 3 x 3 around (0, -1, 0), block 3 not.
        sim.wakeAround(0, -1, 0);
        assertEquals(64, countSleeping(sim, 3), "nothing wakes before the next tick");
        sim.applyWakes();
        assertEquals(0, countSleeping(sim, 0));
        assertEquals(0, countSleeping(sim, 1));
        assertEquals(64, countSleeping(sim, 3));

        // More changes than remembered between two ticks: everything wakes.
        java.util.Arrays.fill(p.sleepTicks, 0, p.activeCount, (short) 500);
        for (int k = 0; k <= FluidSimulation.MAX_PENDING_WAKES; k++) {
            sim.wakeAround(100 + k, 50, 0);
        }
        sim.applyWakes();
        assertEquals(0, countSleeping(sim, 3));
        assertEquals(0, sim.countResting());
    }

    @Test
    void chunksOfParticlesAreCollectedAndMarkedPerChunk() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(15, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        sim.spawn(16, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        sim.spawn(-1, 0, -17, MATERIAL_LAVA, 10, 0f, 0f, 0f);

        it.unimi.dsi.fastutil.longs.LongOpenHashSet chunks = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        sim.collectChunks(chunks);
        assertEquals(3, chunks.size());
        assertTrue(chunks.contains(ChunkKeys.pack(0, 0)));
        assertTrue(chunks.contains(ChunkKeys.pack(1, 0)));
        assertTrue(chunks.contains(ChunkKeys.pack(-1, -2)));

        it.unimi.dsi.fastutil.longs.LongOpenHashSet one = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        one.add(ChunkKeys.pack(1, 0));
        assertEquals(64, sim.markInChunks(one));
        assertEquals(0, sim.markInChunks(one), "particles picked already are not counted again");
        assertEquals(64, sim.removeMarked(null));
        assertEquals(64, countInBlock(sim, 15, 0, 0, MATERIAL_WATER));
        assertEquals(0, countInBlock(sim, 16, 0, 0, MATERIAL_WATER));

        sim.countLeftSimulation(64, 3);
        assertEquals(64, sim.condensedOutside());
        assertEquals(3, sim.removedOutside());
    }

    private static int countSleeping(FluidSimulation sim, int bx) {
        ParticleSoA p = sim.particles();
        int n = 0;
        for (int i = 0; i < p.activeCount; i++) {
            if ((int) Math.floor(p.worldX(i)) == bx && p.sleepTicks[i] > 0) {
                n++;
            }
        }
        return n;
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
