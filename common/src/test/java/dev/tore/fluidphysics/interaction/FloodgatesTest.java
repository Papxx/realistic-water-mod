// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_LAVA;
import static dev.tore.fluidphysics.physics.ParticleSoA.MATERIAL_WATER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class FloodgatesTest {
    private static final int AIR = -1;
    private static final int SOLID = -2;

    /** Blocks in a map: a material id for a source block, {@link #SOLID}, or air when absent. */
    private static final class FakeWorld implements Floodgates.World {
        final Long2IntOpenHashMap blocks = new Long2IntOpenHashMap();
        final LongOpenHashSet unsimulated = new LongOpenHashSet();
        final List<String> converted = new ArrayList<>();
        int room = Integer.MAX_VALUE;

        FakeWorld() {
            blocks.defaultReturnValue(AIR);
        }

        void set(int x, int y, int z, int block) {
            blocks.put(BlockPos.asLong(x, y, z), block);
        }

        @Override
        public boolean isOpen(long key) {
            return blocks.get(key) == AIR;
        }

        @Override
        public int sourceMaterial(long key) {
            int block = blocks.get(key);
            return block >= 0 ? block : -1;
        }

        @Override
        public boolean isSimulated(long key) {
            return !unsimulated.contains(key);
        }

        @Override
        public boolean convert(long key, byte material, float vx, float vy, float vz) {
            if (room < 64) {
                return false;
            }
            room -= 64;
            blocks.remove(key);
            converted.add(material + "@" + BlockPos.getX(key) + "," + BlockPos.getY(key) + "," + BlockPos.getZ(key)
                    + " v" + (int) vx + "," + (int) vy + "," + (int) vz);
            return true;
        }
    }

    /** A wall at x = 0 holding back a reservoir of water sources at x = 1..3, y = 0..2, z = 0. */
    private static FakeWorld dam() {
        FakeWorld world = new FakeWorld();
        for (int y = 0; y <= 2; y++) {
            world.set(0, y, 0, SOLID);
            for (int x = 1; x <= 3; x++) {
                world.set(x, y, 0, MATERIAL_WATER);
            }
        }
        return world;
    }

    @Test
    void anOpeningDrainsTheReservoirAtAndAboveItsLevel() {
        FakeWorld world = dam();
        Floodgates gates = new Floodgates();
        // The middle wall block is broken.
        world.set(0, 1, 0, AIR);
        gates.opened(0, 1, 0);

        assertEquals(6, gates.run(world, 100));
        // First the source right behind the opening, flowing towards it (-x), then onward through the reservoir.
        assertEquals("0@1,1,0 v-1,0,0", world.converted.get(0));
        // Everything at y >= 1 is converted, the row below the breach stays.
        for (int x = 1; x <= 3; x++) {
            assertEquals(AIR, world.blocks.get(BlockPos.asLong(x, 1, 0)));
            assertEquals(AIR, world.blocks.get(BlockPos.asLong(x, 2, 0)));
            assertEquals(MATERIAL_WATER, world.blocks.get(BlockPos.asLong(x, 0, 0)));
        }
        assertFalse(gates.hasPending());
    }

    @Test
    void theBudgetAndTheParticleLimitPauseTheConversion() {
        FakeWorld world = dam();
        Floodgates gates = new Floodgates();
        world.set(0, 1, 0, AIR);
        gates.opened(0, 1, 0);

        assertEquals(2, gates.run(world, 2));
        assertTrue(gates.hasPending());
        // Room for one more block only.
        world.room = 64;
        assertEquals(1, gates.run(world, 100));
        assertTrue(gates.hasPending());
        world.room = Integer.MAX_VALUE;
        assertEquals(3, gates.run(world, 100));
        assertEquals(6, world.converted.size());
        assertFalse(gates.hasPending());
    }

    @Test
    void closedOpeningsUnsimulatedSourcesAndLavaAreHandled() {
        FakeWorld world = new FakeWorld();
        world.set(1, 0, 0, MATERIAL_WATER);
        world.set(-1, 0, 0, MATERIAL_LAVA);
        world.set(0, 0, 1, MATERIAL_WATER);
        world.unsimulated.add(BlockPos.asLong(0, 0, 1));
        Floodgates gates = new Floodgates();

        // Closed again before its turn: nothing happens.
        world.set(0, 0, 0, SOLID);
        gates.opened(0, 0, 0);
        assertEquals(0, gates.run(world, 100));
        assertFalse(gates.hasPending());

        // Open: water and lava pour in, the source outside the simulated area stays. The source below never counts.
        world.set(0, 0, 0, AIR);
        world.set(0, -1, 0, MATERIAL_WATER);
        gates.opened(0, 0, 0);
        gates.opened(0, 0, 0);
        assertEquals(1, gates.pending(), "an opening is queued once");
        assertEquals(2, gates.run(world, 100));
        assertEquals(List.of("0@1,0,0 v-1,0,0", "1@-1,0,0 v1,0,0"), world.converted);
        assertEquals(MATERIAL_WATER, world.blocks.get(BlockPos.asLong(0, 0, 1)));
        assertEquals(MATERIAL_WATER, world.blocks.get(BlockPos.asLong(0, -1, 0)));
    }

    @Test
    void sourcesAbovePourDown() {
        FakeWorld world = new FakeWorld();
        world.set(0, 1, 0, MATERIAL_WATER);
        Floodgates gates = new Floodgates();
        gates.opened(0, 0, 0);
        assertEquals(1, gates.run(world, 100));
        assertEquals(List.of("0@0,1,0 v0,-1,0"), world.converted);
    }
}
