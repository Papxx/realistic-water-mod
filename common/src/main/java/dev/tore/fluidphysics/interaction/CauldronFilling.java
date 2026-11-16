// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import dev.tore.fluidphysics.physics.BlockOccupancy;
import dev.tore.fluidphysics.physics.ChunkVoxelCache;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;

/**
 * Fills vanilla cauldrons with the water and lava particles that fall into them (Plan.md §4.3).
 *
 * <p>A cauldron's bowl has room for only 12 sub-voxels, fewer than the 21 particles of one water level, so particles
 * cannot pile up in it until a level is complete. Instead a cauldron takes in every matching particle that reaches its
 * bowl and keeps count; each complete level raises the vanilla fill level. A bucket of 64 particles fills a water
 * cauldron completely (21 + 22 + 21 particles for levels 1 to 3) or makes a lava cauldron (64 particles), the same
 * amounts a vanilla bucket holds. A cauldron never takes more than it can hold, and the particles it collected
 * towards an unfinished level come back out if it can no longer take them (broken, emptied into lava, ...).
 *
 * <p>Particles that come to rest on the rim (the ring of sub-voxels around the bowl, in the bottom quarter of the block
 * above) count as in the cauldron too: water sitting on the rim would run in.
 *
 * <p>Only cauldrons the voxel cache {@link ChunkVoxelCache#TAG_CAULDRON tags} are looked at, and only when they hold
 * particles. Allocates nothing once its tables have grown.
 */
public final class CauldronFilling {
    /** {@link World#fillState} of a block that is not a cauldron. */
    public static final int NOT_A_CAULDRON = -1;
    /** Fill state of an empty cauldron; 1..3 are water levels. */
    public static final int EMPTY = 0;
    /** Fill state of a lava cauldron. */
    public static final int LAVA = 4;
    /** Fill state of any other cauldron (powder snow), which takes nothing. */
    public static final int OTHER = 5;
    /** {@link World#fillState} of a block in an unloaded chunk: takes nothing, keeps what it collected. */
    public static final int UNKNOWN = 6;
    /** Height above a cauldron's top, in blocks, within which particles count as on its rim. */
    static final float RIM_HEIGHT = 0.3f;
    /** Particles that raise a water cauldron from level {@code i} to {@code i + 1}; they add up to 64. */
    static final int[] WATER_STEP = {21, 22, 21};

    /** What the cauldron filling needs from the level. */
    public interface World {
        /**
         * {@link #EMPTY}, a water level 1..3, {@link #LAVA}, {@link #OTHER}, {@link #NOT_A_CAULDRON} or, if the block is
         * not loaded, {@link #UNKNOWN}.
         */
        int fillState(long key);

        /** Changes the cauldron at {@code key} to a new fill state. */
        void setFillState(long key, int state);

        /** Puts {@code count} particles collected by a cauldron that can no longer take them back into the world. */
        void giveBack(long key, byte material, int count);
    }

    /** Cauldron to the particles it collected towards its next level: {@code material << 16 | count}. */
    private final Long2IntOpenHashMap pending = new Long2IntOpenHashMap();
    private final LongArrayList pendingKeys = new LongArrayList();

    /** Particles collected towards the next level of the cauldron at {@code key} (0 if none). */
    public int pendingCount(long key) {
        return pending.get(key) & 0xFFFF;
    }

    /**
     * Lets every cauldron that holds water or lava particles take them in, marking the particles taken.
     *
     * @return the number of particles marked
     */
    public int run(FluidSimulation simulation, World world) {
        BlockOccupancy occupancy = simulation.occupancy();
        if (occupancy == null) {
            return 0;
        }
        ChunkVoxelCache voxels = simulation.voxels();
        int taken = 0;
        for (int slot = 0; slot < occupancy.size(); slot++) {
            int water = occupancy.count(slot, ParticleSoA.MATERIAL_WATER);
            int lava = occupancy.count(slot, ParticleSoA.MATERIAL_LAVA);
            if (water == 0 && lava == 0) {
                continue;
            }
            long key = occupancy.key(slot);
            int x = BlockPos.getX(key);
            int y = BlockPos.getY(key);
            int z = BlockPos.getZ(key);
            long cauldron;
            float height;
            if ((voxels.getTag(x, y, z) & ChunkVoxelCache.TAG_CAULDRON) != 0) {
                cauldron = key;
                height = 1f;
            } else if ((voxels.getTag(x, y - 1, z) & ChunkVoxelCache.TAG_CAULDRON) != 0) {
                cauldron = BlockPos.asLong(x, y - 1, z);
                height = RIM_HEIGHT;
            } else {
                continue;
            }
            if (water > 0) {
                taken += fill(simulation, world, cauldron, key, height, ParticleSoA.MATERIAL_WATER, water);
            }
            if (lava > 0) {
                taken += fill(simulation, world, cauldron, key, height, ParticleSoA.MATERIAL_LAVA, lava);
            }
        }
        return taken;
    }

    /**
     * Lets the cauldron at {@code key} take particles of {@code material} from block {@code from} (the cauldron itself,
     * or the block above it, of which only the bottom {@code height} counts).
     */
    private int fill(FluidSimulation simulation, World world, long key, long from, float height, byte material,
                     int available) {
        int state = world.fillState(key);
        int stored = pending.get(key);
        if (state == NOT_A_CAULDRON || stored != 0 && (stored >>> 16) != material || !accepts(material, state)) {
            return 0;
        }
        int collected = stored & 0xFFFF;
        int take = Math.min(available, capacity(material, state) - collected);
        if (take <= 0) {
            return 0;
        }
        take = simulation.markInBlock(BlockPos.getX(from), BlockPos.getY(from), BlockPos.getZ(from), material, take,
                height);
        collected += take;
        int newState = state;
        while (accepts(material, newState) && collected >= step(material, newState)) {
            collected -= step(material, newState);
            newState = material == ParticleSoA.MATERIAL_WATER ? newState + 1 : LAVA;
        }
        if (newState != state) {
            world.setFillState(key, newState);
        }
        setPending(key, material, collected);
        return take;
    }

    /**
     * Gives back the particles of cauldrons that can no longer take them: broken, turned into another kind, or full.
     * Cauldrons in unloaded chunks keep theirs. Call now and then (the server does it once a second).
     */
    public void returnStale(World world) {
        for (int k = pendingKeys.size() - 1; k >= 0; k--) {
            long key = pendingKeys.getLong(k);
            int stored = pending.get(key);
            byte material = (byte) (stored >>> 16);
            int state = world.fillState(key);
            if (state != UNKNOWN && !accepts(material, state)) {
                world.giveBack(key, material, stored & 0xFFFF);
                setPending(key, material, 0);
            }
        }
    }

    public void clear() {
        pending.clear();
        pendingKeys.clear();
    }

    private void setPending(long key, byte material, int count) {
        boolean present = pending.containsKey(key);
        if (count > 0) {
            pending.put(key, material << 16 | count);
            if (!present) {
                pendingKeys.add(key);
            }
        } else if (present) {
            pending.remove(key);
            int index = pendingKeys.indexOf(key);
            pendingKeys.set(index, pendingKeys.getLong(pendingKeys.size() - 1));
            pendingKeys.removeLong(pendingKeys.size() - 1);
        }
    }

    /** Whether a cauldron in {@code state} takes particles of {@code material}. */
    static boolean accepts(byte material, int state) {
        if (material == ParticleSoA.MATERIAL_WATER) {
            return state >= EMPTY && state < 3;
        }
        return material == ParticleSoA.MATERIAL_LAVA && state == EMPTY;
    }

    /** Particles that complete the next level of a cauldron in {@code state}. */
    private static int step(byte material, int state) {
        return material == ParticleSoA.MATERIAL_WATER ? WATER_STEP[state] : ParticleSoA.PARTICLES_PER_BLOCK;
    }

    /** Particles a cauldron in {@code state} can still take until it is full. */
    private static int capacity(byte material, int state) {
        if (material == ParticleSoA.MATERIAL_LAVA) {
            return ParticleSoA.PARTICLES_PER_BLOCK;
        }
        int sum = 0;
        for (int level = state; level < 3; level++) {
            sum += WATER_STEP[level];
        }
        return sum;
    }
}
