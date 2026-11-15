// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

import java.util.Arrays;

/**
 * Uniform grid over particle positions, stored as a flat table of {@link #HASH_SIZE} buckets and filled with a
 * counting sort. Rebuilding it allocates nothing and costs O(N + HASH_SIZE).
 *
 * <p>After {@link #build}, the particles of bucket {@code b} are
 * {@code sortedIndices[cellStart[b] .. cellStart[b] + cellCount[b] - 1]}. Different cells can share a bucket,
 * so {@link #sortedCellKey} tells which cell each sorted slot really belongs to.
 *
 * <p>The cell edge equals the solver's smoothing radius, so all neighbours within that radius lie in the
 * 3 x 3 x 3 cells around a particle. (Plan.md first had 0.25-block cells with a 0.35-block radius; a 27-cell
 * search then misses neighbours that sit two cells away.)
 *
 * <p>The bucket of a cell is its linear index inside the bounding box of all particles (x fastest, then z, then y),
 * with each axis wrapped to a power of two so the box fits into the table; a box larger than the table wraps and its
 * far-apart cells share buckets. Unlike a scrambling hash, this keeps neighbouring cells next to each other in bucket
 * order and therefore in the sorted particle order, which made the neighbour search about three times faster.
 * (The multiplicative hash from Plan.md scattered the 27 neighbour cells over the whole particle array.)
 */
public final class SpatialHashGrid {
    public static final float CELL_SIZE = 0.35f;
    public static final float INV_CELL_SIZE = 1.0f / CELL_SIZE;
    public static final int HASH_BITS = 16;
    public static final int HASH_SIZE = 1 << HASH_BITS;

    /** Per-worker slots are this many ints apart, so workers never write to the same cache line. */
    private static final int WORKER_STRIDE = 16;

    /** Particles per bucket. */
    public final int[] cellCount = new int[HASH_SIZE];
    /** First sorted slot of each bucket. */
    public final int[] cellStart = new int[HASH_SIZE];
    /** Particle indices ordered by bucket. */
    public final int[] sortedIndices;
    /** {@link #packCell} key of the particle in each sorted slot. */
    public final int[] sortedCellKey;

    private final int[] cellX;
    private final int[] cellY;
    private final int[] cellZ;
    private final int[] particleHash;
    /** Per worker: min x, y, z and max x, y, z cell of the particles it processed. */
    private final int[] workerBounds;

    // Bucket mapping of the current build.
    private int originX;
    private int originY;
    private int originZ;
    private int maskX;
    private int maskY;
    private int maskZ;
    private int shiftZ;
    private int shiftY;
    private int size;

    /** A grid filled by one thread. */
    public SpatialHashGrid(int capacity) {
        this(capacity, 1);
    }

    /** A grid whose {@link #computeCells} may be split between {@code workers} threads. */
    public SpatialHashGrid(int capacity, int workers) {
        sortedIndices = new int[capacity];
        sortedCellKey = new int[capacity];
        cellX = new int[capacity];
        cellY = new int[capacity];
        cellZ = new int[capacity];
        particleHash = new int[capacity];
        workerBounds = new int[workers * WORKER_STRIDE];
    }

    /** Number of particles in the last build. */
    public int size() {
        return size;
    }

    public static int cellCoord(float v) {
        return (int) Math.floor(v * INV_CELL_SIZE);
    }

    /** Bucket of a cell under the mapping of the last build. */
    public int hashCell(int cx, int cy, int cz) {
        return ((cx - originX) & maskX)
                | (((cz - originZ) & maskZ) << shiftZ)
                | (((cy - originY) & maskY) << shiftY);
    }

    /**
     * Cell coordinates packed into one int, 10 bits per axis (wrapping every 1024 cells). Cells in one 3 x 3 x 3
     * neighbourhood never share a key, which is all the neighbour search needs to skip other cells in a bucket.
     */
    public static int packCell(int cx, int cy, int cz) {
        return (cx & 1023) | ((cy & 1023) << 10) | ((cz & 1023) << 20);
    }

    /** Sorts the first {@code n} positions into the grid. */
    public void build(float[] x, float[] y, float[] z, int n) {
        computeCells(x, y, z, 0, n, 0);
        sort(n, 1);
    }

    /**
     * First half of {@link #build}: the cell of particles {@code from..to-1}. Disjoint ranges may run in parallel,
     * one per {@code worker}.
     */
    public void computeCells(float[] x, float[] y, float[] z, int from, int to, int worker) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = from; i < to; i++) {
            int cx = cellCoord(x[i]);
            int cy = cellCoord(y[i]);
            int cz = cellCoord(z[i]);
            cellX[i] = cx;
            cellY[i] = cy;
            cellZ[i] = cz;
            minX = Math.min(minX, cx);
            minY = Math.min(minY, cy);
            minZ = Math.min(minZ, cz);
            maxX = Math.max(maxX, cx);
            maxY = Math.max(maxY, cy);
            maxZ = Math.max(maxZ, cz);
        }
        int b = worker * WORKER_STRIDE;
        workerBounds[b] = minX;
        workerBounds[b + 1] = minY;
        workerBounds[b + 2] = minZ;
        workerBounds[b + 3] = maxX;
        workerBounds[b + 4] = maxY;
        workerBounds[b + 5] = maxZ;
    }

    /**
     * Second half of {@link #build}: picks the bucket mapping from the bounds found by the first {@code workers}
     * workers, then counting-sorts the first {@code n} particles by bucket.
     */
    public void sort(int n, int workers) {
        chooseMapping(workers);
        Arrays.fill(cellCount, 0);
        for (int i = 0; i < n; i++) {
            int h = hashCell(cellX[i], cellY[i], cellZ[i]);
            particleHash[i] = h;
            cellCount[h]++;
        }
        // Inclusive prefix sum: cellStart[b] is the end of bucket b for now ...
        int sum = 0;
        for (int b = 0; b < HASH_SIZE; b++) {
            sum += cellCount[b];
            cellStart[b] = sum;
        }
        // ... and walking the particles backwards while decrementing turns it into the start (stable order).
        for (int i = n - 1; i >= 0; i--) {
            int slot = --cellStart[particleHash[i]];
            sortedIndices[slot] = i;
            sortedCellKey[slot] = packCell(cellX[i], cellY[i], cellZ[i]);
        }
        size = n;
    }

    private void chooseMapping(int workers) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int w = 0; w < workers; w++) {
            int b = w * WORKER_STRIDE;
            minX = Math.min(minX, workerBounds[b]);
            minY = Math.min(minY, workerBounds[b + 1]);
            minZ = Math.min(minZ, workerBounds[b + 2]);
            maxX = Math.max(maxX, workerBounds[b + 3]);
            maxY = Math.max(maxY, workerBounds[b + 4]);
            maxZ = Math.max(maxZ, workerBounds[b + 5]);
        }
        if (minX > maxX) {
            // No particles.
            minX = minY = minZ = 0;
            maxX = maxY = maxZ = 0;
        }
        // One spare cell on each side, so the neighbour search around the box edge does not wrap onto occupied cells.
        int bitsX = bitsFor((long) maxX - minX + 3);
        int bitsY = bitsFor((long) maxY - minY + 3);
        int bitsZ = bitsFor((long) maxZ - minZ + 3);
        // A box larger than the table wraps along its longest axes.
        while (bitsX + bitsY + bitsZ > HASH_BITS) {
            if (bitsX >= bitsY && bitsX >= bitsZ) {
                bitsX--;
            } else if (bitsZ >= bitsY) {
                bitsZ--;
            } else {
                bitsY--;
            }
        }
        originX = minX - 1;
        originY = minY - 1;
        originZ = minZ - 1;
        maskX = (1 << bitsX) - 1;
        maskY = (1 << bitsY) - 1;
        maskZ = (1 << bitsZ) - 1;
        shiftZ = bitsX;
        shiftY = bitsX + bitsZ;
    }

    /** Smallest number of bits that can count {@code extent} cells (capped at the table size). */
    private static int bitsFor(long extent) {
        if (extent >= HASH_SIZE) {
            return HASH_BITS;
        }
        return 32 - Integer.numberOfLeadingZeros((int) extent - 1);
    }
}
