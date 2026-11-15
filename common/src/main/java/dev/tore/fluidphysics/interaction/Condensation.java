// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import dev.tore.fluidphysics.physics.BlockOccupancy;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

/**
 * Turns resting particles back into vanilla blocks (Plan.md §4.2), so finished pours stop costing solver time.
 *
 * <p>A block is settled for a material when at least three quarters of its particles of that material rest (a pool
 * often keeps a surface particle or two jittering above the sleep speed long after the rest has stopped). Settled
 * blocks of one material that touch (including diagonally) form a cluster. A cluster of {@code C >= 64} particles
 * becomes {@code C / 64} blocks, placed at the lowest blocks of the cluster that can take one (the fuller block first
 * when two are equally low); each block takes the 64 particles of the material nearest to it. A cluster that fills a
 * block on its own is the case Plan.md describes; a cluster spread thinly over several blocks condenses too, which a
 * per-block threshold of 64 would never allow.
 *
 * <p>Water and lava merge into vanilla blocks of their own fluid: when a cluster of fewer than 64 particles (or the
 * rest of a cluster after condensing) touches a source block of the same fluid, it is absorbed. This loses up to 63
 * particles of mass, but nothing is ever created.
 *
 * <p>The scan reads particle and resting counts from {@link FluidSimulation#occupancy()} and decides with a {@link World}; it only
 * marks particles, the caller removes them. Reuses its tables, so a run allocates nothing once they have grown.
 */
public final class Condensation {
    /** Particles one vanilla block takes. */
    public static final int BLOCK_PARTICLES = ParticleSoA.PARTICLES_PER_BLOCK;
    /** Farthest a particle is moved into a condensed block, in blocks. */
    public static final float GATHER_RADIUS = 4.0f;

    /** What the condensation needs from the level. */
    public interface World {
        /** Whether a vanilla block of {@code material} may replace the block at {@code key}. */
        boolean canPlace(long key, byte material);

        /** Whether the block at {@code key} is a vanilla source of the material's own fluid (water and lava only). */
        boolean isOwnSource(long key, byte material);

        /** Places the vanilla block of {@code material} at {@code key}. */
        void place(long key, byte material);
    }

    private final LongOpenHashSet visited = new LongOpenHashSet();
    private final LongOpenHashSet clusterSet = new LongOpenHashSet();
    private final LongArrayList cluster = new LongArrayList();
    /** Cluster blocks tried as a target in the current cluster. */
    private final LongOpenHashSet tried = new LongOpenHashSet();
    /** Cluster blocks that received a vanilla block in the current cluster. */
    private final LongOpenHashSet filled = new LongOpenHashSet();

    /**
     * Condenses every cluster of resting particles that can, marking the particles it uses.
     *
     * @return the number of vanilla blocks placed
     */
    public int run(FluidSimulation simulation, World world) {
        BlockOccupancy occupancy = simulation.occupancy();
        if (occupancy == null || occupancy.size() == 0) {
            return 0;
        }
        int blocks = 0;
        for (byte m = 0; m < ParticleSoA.MATERIAL_COUNT; m++) {
            visited.clear();
            for (int slot = 0; slot < occupancy.size(); slot++) {
                long key = occupancy.key(slot);
                if (settled(occupancy, slot, m) && !visited.contains(key)) {
                    int total = collectCluster(occupancy, key, m);
                    blocks += condenseCluster(simulation, occupancy, world, m, total);
                }
            }
        }
        return blocks;
    }

    /** Whether a slot holds particles of {@code m} and at least three quarters of them rest. */
    private static boolean settled(BlockOccupancy occupancy, int slot, byte m) {
        int count = occupancy.count(slot, m);
        return count > 0 && occupancy.resting(slot, m) * 4 >= count * 3;
    }

    /** Flood-fills the cluster around {@code start} into {@link #cluster}; returns its particle count. */
    private int collectCluster(BlockOccupancy occupancy, long start, byte m) {
        cluster.clear();
        clusterSet.clear();
        cluster.add(start);
        clusterSet.add(start);
        visited.add(start);
        int total = 0;
        for (int head = 0; head < cluster.size(); head++) {
            long key = cluster.getLong(head);
            total += occupancy.count(occupancy.slotOf(key), m);
            int x = BlockPos.getX(key);
            int y = BlockPos.getY(key);
            int z = BlockPos.getZ(key);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        long next = BlockPos.asLong(x + dx, y + dy, z + dz);
                        if (visited.contains(next)) {
                            continue;
                        }
                        int slot = occupancy.slotOf(next);
                        if (slot >= 0 && settled(occupancy, slot, m)) {
                            visited.add(next);
                            cluster.add(next);
                            clusterSet.add(next);
                        }
                    }
                }
            }
        }
        return total;
    }

    private int condenseCluster(FluidSimulation simulation, BlockOccupancy occupancy, World world, byte m,
                                int total) {
        int wanted = total / BLOCK_PARTICLES;
        int blocks = 0;
        tried.clear();
        filled.clear();
        int left = total;
        while (blocks < wanted) {
            long target = lowestPlaceable(occupancy, world, m);
            if (target == Long.MIN_VALUE) {
                break;
            }
            tried.add(target);
            int marked = simulation.markNearest(BlockPos.getX(target) + 0.5, BlockPos.getY(target) + 0.5,
                    BlockPos.getZ(target) + 0.5, m, BLOCK_PARTICLES, GATHER_RADIUS, false, true);
            if (marked == 0) {
                // Too spread out around this block; try the next candidate.
                continue;
            }
            world.place(target, m);
            filled.add(target);
            blocks++;
            left -= marked;
        }
        if (left > 0 && left < BLOCK_PARTICLES && touchesOwnSource(world, m)) {
            markRestInCluster(simulation, m);
        }
        return blocks;
    }

    /** The lowest cluster block that can take a block and was not tried yet; the fuller one on ties. */
    private long lowestPlaceable(BlockOccupancy occupancy, World world, byte m) {
        long best = Long.MIN_VALUE;
        int bestY = Integer.MAX_VALUE;
        int bestCount = -1;
        for (int c = 0; c < cluster.size(); c++) {
            long key = cluster.getLong(c);
            if (tried.contains(key)) {
                continue;
            }
            int y = BlockPos.getY(key);
            int count = occupancy.count(occupancy.slotOf(key), m);
            if ((y < bestY || y == bestY && count > bestCount) && world.canPlace(key, m)) {
                best = key;
                bestY = y;
                bestCount = count;
            }
        }
        return best;
    }

    /** Whether any cluster block is, or is next to, a source block of the material's own vanilla fluid. */
    private boolean touchesOwnSource(World world, byte m) {
        if (m != ParticleSoA.MATERIAL_WATER && m != ParticleSoA.MATERIAL_LAVA) {
            return false;
        }
        for (int c = 0; c < cluster.size(); c++) {
            long key = cluster.getLong(c);
            if (filled.contains(key) || world.isOwnSource(key, m)
                    || world.isOwnSource(BlockPos.offset(key, 0, -1, 0), m)
                    || world.isOwnSource(BlockPos.offset(key, 1, 0, 0), m)
                    || world.isOwnSource(BlockPos.offset(key, -1, 0, 0), m)
                    || world.isOwnSource(BlockPos.offset(key, 0, 0, 1), m)
                    || world.isOwnSource(BlockPos.offset(key, 0, 0, -1), m)
                    || world.isOwnSource(BlockPos.offset(key, 0, 1, 0), m)) {
                return true;
            }
        }
        return false;
    }

    /** Marks every particle of {@code m} left in the cluster's blocks. */
    private void markRestInCluster(FluidSimulation simulation, byte m) {
        ParticleSoA p = simulation.particles();
        for (int i = 0; i < p.activeCount; i++) {
            if (p.material[i] != m || simulation.isMarked(i)) {
                continue;
            }
            long key = BlockPos.asLong(p.originX + (int) Math.floor(p.posX[i]),
                    p.originY + (int) Math.floor(p.posY[i]), p.originZ + (int) Math.floor(p.posZ[i]));
            if (clusterSet.contains(key)) {
                simulation.mark(i);
            }
        }
    }
}
