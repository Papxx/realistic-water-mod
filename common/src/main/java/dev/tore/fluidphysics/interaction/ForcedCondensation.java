// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Arrays;
import net.minecraft.core.BlockPos;

/**
 * Turns every particle in a set of chunks into vanilla blocks at once, whether it rests or not (Plan.md §7.5). The
 * server runs it for chunks that leave the simulated area and for all particles when it stops, so no particle is
 * left in a chunk that unloads and the mass survives a restart as blocks.
 *
 * <p>Each chunk and material is handled on its own: its {@code C} particles become {@code C / 64} blocks, placed at the
 * lowest blocks its particles occupy that can take one (the fuller block first when two are equally low), each taking
 * the 64 particles of the group nearest to it. Whatever is left (fewer than 64 particles, or no free block) is removed
 * as well, so the chunk ends up empty; that loses at most 63 particles per chunk and material and never creates any.
 *
 * <p>Like {@link Condensation} it decides with a {@link Condensation.World} and only marks particles; the caller removes
 * them. Its tables are reused, so a run allocates nothing once they have grown.
 */
public final class ForcedCondensation {
    private static final int BLOCK_PARTICLES = ParticleSoA.PARTICLES_PER_BLOCK;
    private static final int MATERIALS = ParticleSoA.MATERIAL_COUNT;
    /** Offset that makes block y coordinates positive in a {@link #candidateKeys sort key}. */
    private static final long Y_OFFSET = 1L << 20;

    /** Chunk to its index among the chunks of this run. */
    private final Long2IntOpenHashMap chunkIndex = new Long2IntOpenHashMap();
    /** Particles of the run, then sorted by group ({@code chunk index * MATERIALS + material}). */
    private int[] gathered = new int[256];
    private int[] groupOf = new int[256];
    private int[] sorted = new int[256];
    private int[] groupStart = new int[MATERIALS + 1];
    /** Occupied blocks of the current group and their particle counts. */
    private final Long2IntOpenHashMap blockCounts = new Long2IntOpenHashMap();
    private long[] blocks = new long[64];
    /** Sort keys of the candidate blocks: lowest first, then fullest, then the block's index. */
    private long[] candidateKeys = new long[64];
    /** Squared distances of the group's remaining particles to the current target. */
    private float[] distances = new float[256];

    private int condensed;
    private int removed;
    private int placed;

    public ForcedCondensation() {
        chunkIndex.defaultReturnValue(-1);
    }

    /**
     * Condenses every particle in {@code chunks} ({@link dev.tore.fluidphysics.physics.ChunkKeys}) that is not marked yet and marks it.
     *
     * @return the number of particles marked
     */
    public int run(FluidSimulation simulation, LongSet chunks, Condensation.World world) {
        condensed = 0;
        removed = 0;
        placed = 0;
        ParticleSoA p = simulation.particles();
        if (p == null || p.activeCount == 0 || chunks.isEmpty()) {
            return 0;
        }
        int n = gather(simulation, p, chunks);
        if (n == 0) {
            return 0;
        }
        sortByGroup(n);
        int groups = chunkIndex.size() * MATERIALS;
        for (int g = 0; g < groups; g++) {
            int from = groupStart[g];
            int to = groupStart[g + 1];
            if (to > from) {
                condenseGroup(simulation, p, world, (byte) (g % MATERIALS), from, to);
            }
        }
        return condensed + removed;
    }

    /** Particles that became blocks in the last {@link #run}. */
    public int lastCondensed() {
        return condensed;
    }

    /** Particles marked without becoming a block in the last {@link #run}. */
    public int lastRemoved() {
        return removed;
    }

    /** Vanilla blocks placed in the last {@link #run}. */
    public int lastPlaced() {
        return placed;
    }

    /** Collects the unmarked particles in {@code chunks} into {@link #gathered} with their group; returns the count. */
    private int gather(FluidSimulation simulation, ParticleSoA p, LongSet chunks) {
        chunkIndex.clear();
        int n = 0;
        for (int i = 0; i < p.activeCount; i++) {
            if (simulation.isMarked(i)) {
                continue;
            }
            long chunk = simulation.chunkOf(i);
            if (!chunks.contains(chunk)) {
                continue;
            }
            int index = chunkIndex.get(chunk);
            if (index < 0) {
                index = chunkIndex.size();
                chunkIndex.put(chunk, index);
            }
            if (n == gathered.length) {
                gathered = Arrays.copyOf(gathered, n * 2);
                groupOf = Arrays.copyOf(groupOf, n * 2);
            }
            gathered[n] = i;
            groupOf[n] = index * MATERIALS + p.material[i];
            n++;
        }
        return n;
    }

    /** Counting sort of {@link #gathered} by group into {@link #sorted}, with each group's range in {@link #groupStart}. */
    private void sortByGroup(int n) {
        int groups = chunkIndex.size() * MATERIALS;
        if (groupStart.length < groups + 1) {
            groupStart = new int[groups + 1];
        }
        if (sorted.length < n) {
            sorted = new int[gathered.length];
        }
        Arrays.fill(groupStart, 0, groups + 1, 0);
        for (int k = 0; k < n; k++) {
            groupStart[groupOf[k] + 1]++;
        }
        for (int g = 0; g < groups; g++) {
            groupStart[g + 1] += groupStart[g];
        }
        // Place each particle at the next free slot of its group, then restore the starts.
        for (int k = 0; k < n; k++) {
            sorted[groupStart[groupOf[k]]++] = gathered[k];
        }
        for (int g = groups; g > 0; g--) {
            groupStart[g] = groupStart[g - 1];
        }
        groupStart[0] = 0;
    }

    /** Condenses the particles {@code sorted[from..to)}, all of one chunk and material. */
    private void condenseGroup(FluidSimulation simulation, ParticleSoA p, Condensation.World world, byte material,
                               int from, int to) {
        int count = to - from;
        int wanted = count / BLOCK_PARTICLES;
        int remaining = count;
        if (wanted > 0) {
            int candidates = collectCandidates(p, from, to);
            for (int c = 0; c < candidates && wanted > 0; c++) {
                long target = blocks[(int) (candidateKeys[c] & 0xFFFF)];
                if (!world.canPlace(target, material)) {
                    continue;
                }
                takeNearest(p, from, remaining, target);
                // The 64 nearest now sit at the end of the remaining range.
                for (int k = from + remaining - BLOCK_PARTICLES; k < from + remaining; k++) {
                    simulation.mark(sorted[k]);
                }
                remaining -= BLOCK_PARTICLES;
                world.place(target, material);
                placed++;
                wanted--;
                condensed += BLOCK_PARTICLES;
            }
        }
        for (int k = from; k < from + remaining; k++) {
            simulation.mark(sorted[k]);
        }
        removed += remaining;
    }

    /**
     * Collects the blocks occupied by {@code sorted[from..to)} into {@link #blocks} and sorts their keys into
     * {@link #candidateKeys} (lowest first, then fullest).
     *
     * @return the number of blocks
     */
    private int collectCandidates(ParticleSoA p, int from, int to) {
        blockCounts.clear();
        int size = 0;
        for (int k = from; k < to; k++) {
            int i = sorted[k];
            long key = BlockPos.asLong(p.originX + (int) Math.floor(p.posX[i]),
                    p.originY + (int) Math.floor(p.posY[i]), p.originZ + (int) Math.floor(p.posZ[i]));
            if (blockCounts.addTo(key, 1) == 0) {
                if (size == blocks.length) {
                    blocks = Arrays.copyOf(blocks, size * 2);
                }
                blocks[size++] = key;
            }
        }
        if (candidateKeys.length < size) {
            candidateKeys = new long[blocks.length];
        }
        for (int b = 0; b < size; b++) {
            long key = blocks[b];
            long fullness = 0xFFFF - Math.min(blockCounts.get(key), 0xFFFF);
            // A group holds at most 32,768 particles, so at most that many blocks: the index fits into 16 bits.
            candidateKeys[b] = (BlockPos.getY(key) + Y_OFFSET) << 32 | fullness << 16 | b;
        }
        Arrays.sort(candidateKeys, 0, size);
        return size;
    }

    /**
     * Moves the 64 particles of {@code sorted[from..from+remaining)} nearest to the centre of block {@code target} to
     * the end of that range (quickselect, in place).
     */
    private void takeNearest(ParticleSoA p, int from, int remaining, long target) {
        if (distances.length < sorted.length) {
            distances = new float[sorted.length];
        }
        float cx = BlockPos.getX(target) - p.originX + 0.5f;
        float cy = BlockPos.getY(target) - p.originY + 0.5f;
        float cz = BlockPos.getZ(target) - p.originZ + 0.5f;
        int end = from + remaining;
        for (int k = from; k < end; k++) {
            int i = sorted[k];
            float dx = p.posX[i] - cx;
            float dy = p.posY[i] - cy;
            float dz = p.posZ[i] - cz;
            // Negated, so the selection of the largest values below picks the nearest particles.
            distances[k] = -(dx * dx + dy * dy + dz * dz);
        }
        // Select so that [end - 64, end) holds the 64 largest negated distances.
        int lo = from;
        int hi = end - 1;
        int split = end - BLOCK_PARTICLES;
        while (lo < hi) {
            float pivot = distances[(lo + hi) >>> 1];
            int i = lo;
            int j = hi;
            while (i <= j) {
                while (distances[i] < pivot) {
                    i++;
                }
                while (distances[j] > pivot) {
                    j--;
                }
                if (i <= j) {
                    swap(i, j);
                    i++;
                    j--;
                }
            }
            // Now [lo..j] <= pivot <= [i..hi]; continue in the part that holds the split.
            if (split <= j) {
                hi = j;
            } else if (split >= i) {
                lo = i;
            } else {
                return;
            }
        }
    }

    private void swap(int a, int b) {
        int index = sorted[a];
        sorted[a] = sorted[b];
        sorted[b] = index;
        float d = distances[a];
        distances[a] = distances[b];
        distances[b] = d;
    }
}
