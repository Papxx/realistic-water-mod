// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_LAVA;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_WATER;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tore.fluidphysics.physics.ChunkVoxelCache;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.WorkerPool;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class CauldronFillingTest {
    private static final WorkerPool POOL = new WorkerPool(1);
    private static final long CAULDRON = BlockPos.asLong(2, 0, 3);

    @AfterAll
    static void closePool() {
        POOL.close();
    }

    /** Cauldrons in a map; anything else is not a cauldron. */
    private static final class FakeWorld implements CauldronFilling.World {
        final Long2IntOpenHashMap states = new Long2IntOpenHashMap();
        final List<String> givenBack = new ArrayList<>();

        FakeWorld() {
            states.defaultReturnValue(CauldronFilling.NOT_A_CAULDRON);
        }

        @Override
        public int fillState(long key) {
            return states.get(key);
        }

        @Override
        public void setFillState(long key, int state) {
            states.put(key, state);
        }

        @Override
        public void giveBack(long key, byte material, int count) {
            givenBack.add(material + "x" + count);
        }
    }

    private static FluidSimulation simWithCauldron() {
        return new FluidSimulation(POOL, 1000);
    }

    private static void tagCauldron(FluidSimulation sim) {
        sim.voxels().putTag(2, 0, 3, ChunkVoxelCache.TAG_CAULDRON);
    }

    @Test
    void aBucketOfWaterFillsACauldronExactly() {
        FluidSimulation sim = simWithCauldron();
        sim.spawn(2, 0, 3, MATERIAL_WATER, 64, 0f, 0f, 0f);
        sim.spawn(2, 0, 3, MATERIAL_WATER, 10, 0f, 0f, 0f);
        tagCauldron(sim);
        FakeWorld world = new FakeWorld();
        world.states.put(CAULDRON, CauldronFilling.EMPTY);
        CauldronFilling filling = new CauldronFilling();

        assertEquals(64, filling.run(sim, world));
        assertEquals(64, sim.removeMarked(null));
        assertEquals(3, world.states.get(CAULDRON));
        assertEquals(0, filling.pendingCount(CAULDRON));
        // Full: the other 10 stay.
        assertEquals(0, filling.run(sim, world));
        assertEquals(10, sim.activeCount());
    }

    @Test
    void partialLevelsWaitForMoreParticles() {
        FluidSimulation sim = simWithCauldron();
        sim.spawn(2, 0, 3, MATERIAL_WATER, 30, 0f, 0f, 0f);
        tagCauldron(sim);
        FakeWorld world = new FakeWorld();
        world.states.put(CAULDRON, CauldronFilling.EMPTY);
        CauldronFilling filling = new CauldronFilling();

        assertEquals(30, filling.run(sim, world));
        sim.removeMarked(null);
        assertEquals(1, world.states.get(CAULDRON));
        assertEquals(9, filling.pendingCount(CAULDRON));

        // The cauldron is broken: the 9 particles come back out.
        world.states.remove(CAULDRON);
        filling.returnStale(world);
        assertEquals(List.of(MATERIAL_WATER + "x9"), world.givenBack);
        assertEquals(0, filling.pendingCount(CAULDRON));
    }

    @Test
    void lavaNeedsAWholeBucketAndOnlyAnEmptyCauldron() {
        FluidSimulation sim = simWithCauldron();
        sim.spawn(2, 0, 3, MATERIAL_LAVA, 40, 0f, 0f, 0f);
        tagCauldron(sim);
        FakeWorld world = new FakeWorld();
        world.states.put(CAULDRON, 1);
        CauldronFilling filling = new CauldronFilling();

        // A water cauldron takes no lava.
        assertEquals(0, filling.run(sim, world));

        world.states.put(CAULDRON, CauldronFilling.EMPTY);
        assertEquals(40, filling.run(sim, world));
        sim.removeMarked(null);
        assertEquals(CauldronFilling.EMPTY, world.states.get(CAULDRON));
        // The simulation is empty now, so this spawn starts over and re-reads the world: tag the cauldron again.
        sim.spawn(2, 0, 3, MATERIAL_LAVA, 30, 0f, 0f, 0f);
        tagCauldron(sim);
        assertEquals(24, filling.run(sim, world));
        assertEquals(CauldronFilling.LAVA, world.states.get(CAULDRON));
    }

    @Test
    void particlesOnTheRimRunIn() {
        FluidSimulation sim = simWithCauldron();
        // A whole block of particles right above the cauldron: only the bottom layer sits on the rim.
        sim.spawn(2, 1, 3, MATERIAL_WATER, 64, 0f, 0f, 0f);
        tagCauldron(sim);
        FakeWorld world = new FakeWorld();
        world.states.put(CAULDRON, CauldronFilling.EMPTY);

        assertEquals(16, new CauldronFilling().run(sim, world));
        assertEquals(CauldronFilling.EMPTY, world.states.get(CAULDRON));
    }

    @Test
    void particlesOutsideCauldronsAreIgnored() {
        FluidSimulation sim = simWithCauldron();
        sim.spawn(5, 0, 5, MATERIAL_WATER, 64, 0f, 0f, 0f);
        FakeWorld world = new FakeWorld();
        assertEquals(0, new CauldronFilling().run(sim, world));
    }
}
