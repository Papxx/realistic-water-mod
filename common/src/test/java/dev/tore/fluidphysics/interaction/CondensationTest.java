// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_SAND;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_WATER;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.physics.WorkerPool;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class CondensationTest {
    private static final WorkerPool POOL = new WorkerPool(1);

    @AfterAll
    static void closePool() {
        POOL.close();
    }

    /** Every block is free unless listed as blocked; water sources are listed too. */
    private static final class FakeWorld implements Condensation.World {
        final LongOpenHashSet blocked = new LongOpenHashSet();
        final LongOpenHashSet waterSources = new LongOpenHashSet();
        final List<String> placed = new ArrayList<>();

        @Override
        public boolean canPlace(long key, byte material) {
            return !blocked.contains(key) && !waterSources.contains(key);
        }

        @Override
        public boolean isOwnSource(long key, byte material) {
            return material == MATERIAL_WATER && waterSources.contains(key);
        }

        @Override
        public void place(long key, byte material) {
            placed.add(material + "@" + BlockPos.getX(key) + "," + BlockPos.getY(key) + "," + BlockPos.getZ(key));
        }
    }

    private static void restAll(FluidSimulation sim) {
        ParticleSoA p = sim.particles();
        for (int i = 0; i < p.activeCount; i++) {
            p.sleepTicks[i] = (short) (FluidSimulation.RESTING_TICKS + 1);
        }
    }

    @Test
    void aFullBlockOfRestingParticlesCondenses() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(4, 10, 4, MATERIAL_WATER, 64, 0f, 0f, 0f);
        restAll(sim);
        FakeWorld world = new FakeWorld();

        assertEquals(1, new Condensation().run(sim, world));
        assertEquals(List.of(MATERIAL_WATER + "@4,10,4"), world.placed);
        assertEquals(64, sim.removeMarked(null));
    }

    @Test
    void aFewJitteringParticlesDoNotHoldBackAPool() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(4, 10, 4, MATERIAL_WATER, 64, 0f, 0f, 0f);
        restAll(sim);
        sim.particles().sleepTicks[7] = 0;
        sim.particles().sleepTicks[40] = 3;
        FakeWorld world = new FakeWorld();

        assertEquals(1, new Condensation().run(sim, world));
        assertEquals(64, sim.removeMarked(null));
    }

    @Test
    void particlesThatAreNotRestingStay() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(4, 10, 4, MATERIAL_WATER, 64, 0f, 0f, 0f);
        assertEquals(0, new Condensation().run(sim, new FakeWorld()));
    }

    @Test
    void aThinClusterCondensesAtItsLowestFullestBlock() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        // 64 sand particles spread over four blocks in a row, the fullest one in the middle, plus one block up.
        sim.spawn(0, 5, 0, MATERIAL_SAND, 10, 0f, 0f, 0f);
        sim.spawn(1, 5, 0, MATERIAL_SAND, 20, 0f, 0f, 0f);
        sim.spawn(2, 5, 0, MATERIAL_SAND, 12, 0f, 0f, 0f);
        sim.spawn(1, 6, 0, MATERIAL_SAND, 22, 0f, 0f, 0f);
        restAll(sim);
        FakeWorld world = new FakeWorld();

        assertEquals(1, new Condensation().run(sim, world));
        assertEquals(List.of(MATERIAL_SAND + "@1,5,0"), world.placed);
        assertEquals(64, sim.removeMarked(null));
    }

    @Test
    void aDeepColumnBecomesAsManyBlocksAsItFills() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(0, 0, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        sim.spawn(0, 1, 0, MATERIAL_WATER, 64, 0f, 0f, 0f);
        sim.spawn(0, 2, 0, MATERIAL_WATER, 30, 0f, 0f, 0f);
        restAll(sim);
        FakeWorld world = new FakeWorld();

        assertEquals(2, new Condensation().run(sim, world));
        assertEquals(List.of(MATERIAL_WATER + "@0,0,0", MATERIAL_WATER + "@0,1,0"), world.placed);
        // The 30 left over touch the new water blocks and merge into them.
        assertEquals(158, sim.removeMarked(null));
    }

    @Test
    void smallWaterClustersMergeIntoAdjacentSourcesButSandStays() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(0, 1, 0, MATERIAL_WATER, 20, 0f, 0f, 0f);
        sim.spawn(10, 1, 0, MATERIAL_SAND, 20, 0f, 0f, 0f);
        sim.spawn(20, 1, 0, MATERIAL_WATER, 20, 0f, 0f, 0f);
        restAll(sim);
        FakeWorld world = new FakeWorld();
        world.waterSources.add(BlockPos.asLong(0, 0, 0));
        world.waterSources.add(BlockPos.asLong(10, 0, 0));

        assertEquals(0, new Condensation().run(sim, world));
        // Only the water on top of a water source merges; the sand on one and the water far away stay.
        assertEquals(20, sim.removeMarked(null));
        assertEquals(40, sim.activeCount());
    }

    @Test
    void blockedBlocksAreSkipped() {
        FluidSimulation sim = new FluidSimulation(POOL, 1000);
        sim.spawn(0, 0, 0, MATERIAL_SAND, 64, 0f, 0f, 0f);
        sim.spawn(0, 1, 0, MATERIAL_SAND, 10, 0f, 0f, 0f);
        restAll(sim);
        FakeWorld world = new FakeWorld();
        world.blocked.add(BlockPos.asLong(0, 0, 0));

        assertEquals(1, new Condensation().run(sim, world));
        assertEquals(List.of(MATERIAL_SAND + "@0,1,0"), world.placed);
    }
}
