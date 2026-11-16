// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_LAVA;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_SAND;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_WATER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.physics.WorkerPool;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import dev.tore.fluidphysics.physics.ChunkKeys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class ForcedCondensationTest {
    private static final WorkerPool POOL = new WorkerPool(1);

    @AfterAll
    static void closePool() {
        POOL.close();
    }

    /** Every block is free unless blocked; records each placement with the particles marked at that moment. */
    private static final class FakeWorld implements Condensation.World {
        final LongOpenHashSet blocked = new LongOpenHashSet();
        final List<String> placed = new ArrayList<>();
        FluidSimulation sim;
        /** For each placement: how many marked particles sat in the placed block when it was placed. */
        final List<Integer> markedInTarget = new ArrayList<>();

        @Override
        public boolean canPlace(long key, byte material) {
            return !blocked.contains(key);
        }

        @Override
        public boolean isOwnSource(long key, byte material) {
            return false;
        }

        @Override
        public void place(long key, byte material) {
            placed.add(material + "@" + BlockPos.getX(key) + "," + BlockPos.getY(key) + "," + BlockPos.getZ(key));
            if (sim != null) {
                ParticleSoA p = sim.particles();
                int marked = 0;
                for (int i = 0; i < p.activeCount; i++) {
                    if (sim.isMarked(i) && (int) Math.floor(p.worldX(i)) == BlockPos.getX(key)
                            && (int) Math.floor(p.worldY(i)) == BlockPos.getY(key)
                            && (int) Math.floor(p.worldZ(i)) == BlockPos.getZ(key)) {
                        marked++;
                    }
                }
                markedInTarget.add(marked);
            }
        }
    }

    private static LongOpenHashSet chunks(int... xz) {
        LongOpenHashSet set = new LongOpenHashSet();
        for (int k = 0; k < xz.length; k += 2) {
            set.add(ChunkKeys.pack(xz[k], xz[k + 1]));
        }
        return set;
    }

    @Test
    void everyParticleOfTheChunkBecomesABlockOrIsRemoved() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        // 158 particles, partly moving: forced condensation does not wait for them to rest. The blocks are not
        // adjacent, so no particle of another block is as near to a target as the corners of its own.
        sim.spawn(4, 10, 4, MATERIAL_WATER, 64, 0f, -2f, 0f);
        sim.spawn(6, 10, 4, MATERIAL_WATER, 30, 0f, 0f, 0f);
        sim.spawn(4, 13, 4, MATERIAL_WATER, 64, 3f, 0f, 0f);
        FakeWorld world = new FakeWorld();
        world.sim = sim;
        ForcedCondensation forced = new ForcedCondensation();

        assertEquals(158, forced.run(sim, chunks(0, 0), world));
        assertEquals(128, forced.lastCondensed());
        assertEquals(30, forced.lastRemoved());
        assertEquals(2, forced.lastPlaced());
        // Lowest first, and of the two lowest the fuller one first.
        assertEquals(List.of(MATERIAL_WATER + "@4,10,4", MATERIAL_WATER + "@6,10,4"), world.placed);
        // The first block took exactly its own 64 particles; the second its own 30 and the 34 nearest of the rest.
        assertEquals(List.of(64, 30), world.markedInTarget);
        assertEquals(158, sim.removeMarked(null));
        assertEquals(0, sim.activeCount());
    }

    @Test
    void onlyTheGivenChunksAreTouched() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(15, 5, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        sim.spawn(16, 5, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        sim.spawn(16, 5, 1, MATERIAL_WATER, 40, 0f, 0f, 0f);
        FakeWorld world = new FakeWorld();
        ForcedCondensation forced = new ForcedCondensation();

        // Chunk (1, 0) holds 104 particles: one block, and the 40 left over are removed. Chunk (0, 0) keeps its 64,
        // although they are nearer to some targets than particles of chunk (1, 0).
        assertEquals(104, forced.run(sim, chunks(1, 0), world));
        assertEquals(List.of(MATERIAL_WATER + "@16,5,0"), world.placed);
        assertEquals(104, sim.removeMarked(null));
        assertEquals(64, sim.activeCount());
        for (int i = 0; i < sim.activeCount(); i++) {
            assertEquals(15, (int) Math.floor(sim.particles().worldX(i)));
        }
    }

    @Test
    void materialsAreCondensedSeparately() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(2, 0, 2, MATERIAL_WATER, 40, 0f, 0f, 0f);
        sim.spawn(2, 0, 2, MATERIAL_LAVA, 40, 0f, 0f, 0f);
        sim.spawn(3, 0, 2, MATERIAL_SAND, 64, 0f, 0f, 0f);
        FakeWorld world = new FakeWorld();
        ForcedCondensation forced = new ForcedCondensation();

        // 80 liquid particles in one block, but only 40 of each kind: neither makes a block. Sand does.
        assertEquals(144, forced.run(sim, chunks(0, 0), world));
        assertEquals(List.of(MATERIAL_SAND + "@3,0,2"), world.placed);
        assertEquals(64, forced.lastCondensed());
        assertEquals(80, forced.lastRemoved());
    }

    @Test
    void blockedBlocksAreSkippedAndWithoutAFreeBlockEverythingIsRemoved() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(0, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        sim.spawn(0, 1, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        FakeWorld world = new FakeWorld();
        world.blocked.add(BlockPos.asLong(0, 0, 0));
        ForcedCondensation forced = new ForcedCondensation();

        // The lower block is taken: both blocks' worth of particles can only go to the upper one, so one block.
        assertEquals(128, forced.run(sim, chunks(0, 0), world));
        assertEquals(List.of(MATERIAL_WATER + "@0,1,0"), world.placed);
        assertEquals(64, forced.lastRemoved());
        sim.removeMarked(null);

        sim.spawn(0, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        world.placed.clear();
        world.blocked.add(BlockPos.asLong(0, 0, 0));
        assertEquals(64, forced.run(sim, chunks(0, 0), world));
        assertTrue(world.placed.isEmpty());
        assertEquals(64, forced.lastRemoved());
    }

    @Test
    void particlesMarkedBeforeAreLeftAlone() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(0, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        sim.spawn(1, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        assertEquals(10, sim.markInBlock(1, 0, 0, MATERIAL_WATER, 10));
        FakeWorld world = new FakeWorld();
        ForcedCondensation forced = new ForcedCondensation();

        assertEquals(118, forced.run(sim, chunks(0, 0), world));
        assertEquals(1, forced.lastPlaced());
        assertEquals(54, forced.lastRemoved());
        assertEquals(128, sim.removeMarked(null));
    }

    @Test
    void repeatedRunsAllocateNothing() {
        FluidSimulation sim = new FluidSimulation(POOL, 4096);
        Condensation.World world = new Condensation.World() {
            @Override
            public boolean canPlace(long key, byte material) {
                return true;
            }

            @Override
            public boolean isOwnSource(long key, byte material) {
                return false;
            }

            @Override
            public void place(long key, byte material) {
            }
        };
        ForcedCondensation forced = new ForcedCondensation();
        LongOpenHashSet chunk = chunks(0, 0);
        // Warm-up: the first rounds grow the tables.
        for (int round = 0; round < 25; round++) {
            spawnRow(sim, round);
            forced.run(sim, chunk, world);
            sim.removeMarked(null);
        }
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long allocated = 0L;
        for (int round = 0; round < 10; round++) {
            // Spawning is not measured.
            spawnRow(sim, round);
            long before = threads.getCurrentThreadAllocatedBytes();
            forced.run(sim, chunk, world);
            sim.removeMarked(null);
            allocated += threads.getCurrentThreadAllocatedBytes() - before;
        }
        assertEquals(0L, allocated, "forced condensation must not allocate once its tables have grown");
    }

    /** 8 blocks of water and 20 sand particles in a row. */
    private static void spawnRow(FluidSimulation sim, int round) {
        for (int b = 0; b < 8; b++) {
            sim.spawn(b, round % 3, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        }
        sim.spawn(9, round % 3, 0, MATERIAL_SAND, 20, 0f, 0f, 0f);
    }
}
