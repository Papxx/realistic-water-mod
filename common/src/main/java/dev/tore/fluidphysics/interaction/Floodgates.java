// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import dev.tore.fluidphysics.physics.ParticleSoA;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

/**
 * Bursting dams (Plan.md §7.3.3, optional): when a block that held back vanilla water or lava is removed, the vanilla
 * source blocks next to the opening turn into 64 particles each, which pour through it.
 *
 * <p>Each converted source becomes an opening itself, so the conversion spreads through the reservoir: sideways and
 * from above, never from below (water below the breach stays where it is). It is limited per tick (see {@link #run})
 * and by the particle limit; an opening that cannot be finished for lack of room waits at the front of the queue
 * until there is room again.
 *
 * <p>Decides with a {@link World}, so it can be tested without a level. Allocates nothing once its queue has grown.
 */
public final class Floodgates {
    /** Most openings remembered; further openings are ignored until the queue drains. */
    public static final int MAX_QUEUED = 4096;
    /** Speed of the new particles towards the opening, in blocks per second. */
    public static final float OUTFLOW_SPEED = 1.0f;
    /** Neighbours of an opening whose sources pour into it: the four sides and the block above. */
    private static final int[][] FEEDERS = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};

    /** What the floodgates need from the level. */
    public interface World {
        /** Whether particles and water can pass the block: nothing to collide with and no fluid source. */
        boolean isOpen(long key);

        /**
         * {@link ParticleSoA#MATERIAL_WATER} or {@link ParticleSoA#MATERIAL_LAVA} if the block is a plain vanilla source
         * block of that fluid (not a waterlogged block), otherwise -1.
         */
        int sourceMaterial(long key);

        /** Whether the block is in the simulated area (loaded and ticking). */
        boolean isSimulated(long key);

        /**
         * Replaces the source block with air and spawns 64 particles of {@code material} in it, moving at the given
         * velocity (blocks per second).
         *
         * @return false if there is no room for 64 particles; then nothing changed
         */
        boolean convert(long key, byte material, float vx, float vy, float vz);
    }

    private final LongArrayFIFOQueue openings = new LongArrayFIFOQueue();
    private final LongOpenHashSet queued = new LongOpenHashSet();

    /** Reports that block ({@code x}, {@code y}, {@code z}) stopped holding anything back. */
    public void opened(int x, int y, int z) {
        opened(BlockPos.asLong(x, y, z));
    }

    private void opened(long key) {
        if (queued.size() < MAX_QUEUED && queued.add(key)) {
            openings.enqueue(key);
        }
    }

    /** Whether openings wait to be handled. */
    public boolean hasPending() {
        return !openings.isEmpty();
    }

    /** Openings waiting to be handled. */
    public int pending() {
        return openings.size();
    }

    /**
     * Handles the waiting openings in the order they appeared, converting at most {@code budget} source blocks.
     *
     * @return the number of source blocks converted
     */
    public int run(World world, int budget) {
        int converted = 0;
        while (!openings.isEmpty()) {
            long opening = openings.firstLong();
            if (!world.isOpen(opening)) {
                // Closed again (or filled with a source) before its turn.
                dequeue();
                continue;
            }
            int x = BlockPos.getX(opening);
            int y = BlockPos.getY(opening);
            int z = BlockPos.getZ(opening);
            for (int[] d : FEEDERS) {
                long feeder = BlockPos.asLong(x + d[0], y + d[1], z + d[2]);
                int material = world.sourceMaterial(feeder);
                if (material < 0 || !world.isSimulated(feeder)) {
                    continue;
                }
                if (converted == budget || !world.convert(feeder, (byte) material,
                        -d[0] * OUTFLOW_SPEED, -d[1] * OUTFLOW_SPEED, -d[2] * OUTFLOW_SPEED)) {
                    // Out of budget or room: finish this opening later; the sources converted so far are air now.
                    return converted;
                }
                converted++;
                opened(feeder);
            }
            dequeue();
        }
        return converted;
    }

    /** Forgets all waiting openings. */
    public void clear() {
        openings.clear();
        queued.clear();
    }

    private void dequeue() {
        queued.remove(openings.dequeueLong());
    }
}
