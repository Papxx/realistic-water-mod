// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import dev.tore.fluidphysics.physics.ParticleSoA;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;

/**
 * Collects cooled lava particles into stone blocks. A full block of lava (64 particles) that cools becomes obsidian,
 * like a vanilla lava source. A formation that stops growing becomes obsidian too if it holds at least three quarters
 * of a block (when a bucket of lava meets a bucket of water, a few particles always miss each other), and
 * cobblestone, like flowing lava, if it holds less.
 *
 * <p>Cooled particles join the stone forming nearby: each one adds to the block within one block of it that has
 * collected the most so far, so a cooling pool fills a few blocks instead of scattering progress over many. 64 cooled
 * particles make one obsidian block; leftovers below {@link #COBBLE_MINIMUM} vanish as steam.
 */
public final class StoneFormation {
    /** Cooled particles that make a block of obsidian. */
    public static final int OBSIDIAN_PARTICLES = ParticleSoA.PARTICLES_PER_BLOCK;
    /** Least particles a stalled formation needs to become obsidian anyway. */
    public static final int STALLED_OBSIDIAN_MINIMUM = 48;
    /** Least particles a stalled formation needs to leave a cobblestone block behind. */
    public static final int COBBLE_MINIMUM = 16;
    /** Ticks without new particles after which a formation counts as stalled. */
    public static final int STALL_TICKS = 40;

    /** Places a block for a finished formation; returns false if the block could not be placed. */
    public interface Placer {
        boolean place(int x, int y, int z, boolean obsidian);
    }

    /** Block to cooled particles collected there. */
    private final Long2IntOpenHashMap progress = new Long2IntOpenHashMap();
    /** Block to the tick it last grew. */
    private final Long2LongOpenHashMap lastGrowth = new Long2LongOpenHashMap();
    /** The keys of {@link #progress}, for iterating without allocating. */
    private final LongArrayList keys = new LongArrayList();

    /** Number of blocks with stone forming. */
    public int size() {
        return progress.size();
    }

    /** Cooled particles collected at a block. */
    public int progressAt(int x, int y, int z) {
        return progress.get(BlockPos.asLong(x, y, z));
    }

    /**
     * Adds one cooled particle near block ({@code x}, {@code y}, {@code z}) and places obsidian once a formation is
     * complete.
     */
    public void add(int x, int y, int z, long tick, Placer placer) {
        long target = BlockPos.asLong(x, y, z);
        int best = progress.get(target);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    long key = BlockPos.asLong(x + dx, y + dy, z + dz);
                    int value = progress.get(key);
                    if (value > best) {
                        best = value;
                        target = key;
                    }
                }
            }
        }
        int value = best + 1;
        if (!progress.containsKey(target)) {
            keys.add(target);
        }
        lastGrowth.put(target, tick);
        if (value >= OBSIDIAN_PARTICLES && placer.place(BlockPos.getX(target), BlockPos.getY(target),
                BlockPos.getZ(target), true)) {
            value -= OBSIDIAN_PARTICLES;
        }
        if (value > 0) {
            progress.put(target, value);
        } else {
            remove(target);
        }
    }

    /** Finishes formations that stopped growing: obsidian or cobblestone by size, steam if too small. */
    public void finishStalled(long tick, Placer placer) {
        for (int k = keys.size() - 1; k >= 0; k--) {
            long key = keys.getLong(k);
            if (tick - lastGrowth.get(key) < STALL_TICKS) {
                continue;
            }
            int collected = progress.get(key);
            if (collected >= COBBLE_MINIMUM) {
                placer.place(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key),
                        collected >= STALLED_OBSIDIAN_MINIMUM);
            }
            remove(key);
        }
    }

    public void clear() {
        progress.clear();
        lastGrowth.clear();
        keys.clear();
    }

    private void remove(long key) {
        progress.remove(key);
        lastGrowth.remove(key);
        int index = keys.indexOf(key);
        if (index >= 0) {
            // Order does not matter: swap the last key in.
            keys.set(index, keys.getLong(keys.size() - 1));
            keys.removeLong(keys.size() - 1);
        }
    }
}
