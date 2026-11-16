// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

import java.util.Arrays;
import net.minecraft.world.level.Level;

/**
 * The particle simulation of one level: its particles, voxel cache and solver, plus the operations that network events
 * and commands perform on them (spawn, impulse, condense, clear).
 *
 * <p>Memory is allocated lazily. Nothing is allocated before the first particle spawns; then the arrays start at
 * {@link #INITIAL_CAPACITY} particles and double when a spawn needs more room, up to the {@link #limit()}. Growing
 * happens while spawning, never inside {@link #tick}. {@link #clear()} frees the arrays again.
 *
 * <p>Lowering the limit keeps the particles that already exist and only stops new spawns. A server must not drop
 * particles on its own, because its clients keep simulating them.
 *
 * <p>Not thread-safe: the thread that ticks the level owns the simulation (the server thread for a server level, the
 * render thread on the client).
 */
public final class FluidSimulation {
    /**
     * Furthest a spawn may be from the position origin on any axis, in blocks. Positions are floats relative to the
     * origin, which keep about 0.0005 blocks of precision at this distance (see {@link ParticleSoA}).
     */
    public static final int MAX_ORIGIN_DISTANCE = 4096;
    /** Particles that stayed slow for more than this many ticks count as resting (the condensation threshold). */
    public static final int RESTING_TICKS = 60;
    /** Particles this many blocks below the bottom of the world are removed; there is nothing left to land on. */
    public static final int VOID_DEPTH = 16;
    /** Largest radius of {@link #applyImpulse}, in blocks. */
    public static final float MAX_IMPULSE_RADIUS = 32f;
    /** Largest velocity change of {@link #applyImpulse}, in blocks per second (the solver's speed limit applies after). */
    public static final float MAX_IMPULSE_FORCE = 50f;
    /** Size of the first allocation; later allocations double it. */
    static final int INITIAL_CAPACITY = 4096;

    private final WorkerPool pool;
    private final ChunkVoxelCache voxels = new ChunkVoxelCache();
    private ParticleSoA particles;
    private PbfSolver solver;
    private int limit;
    /** Particles picked for {@link #removeMarked}, by particle index; all 0 between removals. */
    private boolean[] marks;
    /** Scratch for {@link #markNearest}: candidate indices and their squared distances. */
    private int[] candidates;
    private float[] candidateDistances;

    /** Bumped whenever particles move, appear or disappear; {@link #occupancy()} is rebuilt when it changed. */
    private int generation;
    private BlockOccupancy occupancy;
    private int occupancyGeneration = -1;

    private final SimulationStats stats = new SimulationStats();
    private long removedInVoid;

    /**
     * @param pool  threads for the solver; may be shared with other simulations that step on the same thread
     * @param limit most particles this simulation holds, see {@link #setLimit}
     */
    public FluidSimulation(WorkerPool pool, int limit) {
        this.pool = pool;
        setLimit(limit);
    }

    public int limit() {
        return limit;
    }

    /**
     * Sets the most particles this simulation holds, clamped to {@code 0..MAX_PARTICLES}. Existing particles above a
     * lowered limit stay; only new spawns are refused until the count drops below it.
     */
    public void setLimit(int newLimit) {
        limit = Math.max(0, Math.min(newLimit, ParticleSoA.MAX_PARTICLES));
    }

    public int activeCount() {
        return particles == null ? 0 : particles.activeCount;
    }

    /** Allocated particle slots; 0 while no arrays are allocated. */
    public int capacity() {
        return particles == null ? 0 : particles.capacity;
    }

    /**
     * The particles, or null while none are allocated. For rendering and statistics: add and remove particles only
     * through this class.
     */
    public ParticleSoA particles() {
        return particles;
    }

    public ChunkVoxelCache voxels() {
        return voxels;
    }

    public int workerThreads() {
        return pool.size();
    }

    /** Whether block ({@code x}, {@code y}, {@code z}) is close enough to the existing particles to spawn there. */
    public boolean canSpawnAt(int blockX, int blockY, int blockZ) {
        ParticleSoA p = particles;
        return p == null || p.activeCount == 0
                || Math.abs((long) blockX - p.originX) <= MAX_ORIGIN_DISTANCE
                && Math.abs((long) blockY - p.originY) <= MAX_ORIGIN_DISTANCE
                && Math.abs((long) blockZ - p.originZ) <= MAX_ORIGIN_DISTANCE;
    }

    /**
     * Spawns up to {@code count} particles (at most 64) on the 4 x 4 x 4 micro-grid of a block, as an emptied bucket or
     * a crumbling block does. Values from the network are safe to pass: unknown materials spawn nothing and non-finite
     * velocities become 0.
     *
     * @param vx initial velocity in blocks per second
     * @return the number of particles spawned: fewer than asked when the limit is reached, 0 for an unknown material
     *         or a block too far from the existing particles (see {@link #canSpawnAt})
     */
    public int spawn(int blockX, int blockY, int blockZ, byte material, int count, float vx, float vy, float vz) {
        int n = Math.min(Math.min(count, ParticleSoA.PARTICLES_PER_BLOCK), limit - activeCount());
        if (n <= 0 || !MaterialProperties.isValid(material) || !canSpawnAt(blockX, blockY, blockZ)) {
            return 0;
        }
        ensureCapacity(activeCount() + n);
        if (particles.activeCount == 0) {
            // A fresh start: read the world again, so blocks changed since the last particles are seen, and keep the
            // float positions precise around the new particles.
            voxels.clear();
            particles.setOrigin(blockX, blockY, blockZ);
        }
        int spawned = particles.spawnBlock(blockX, blockY, blockZ, material, n, finite(vx), finite(vy), finite(vz));
        particlesChanged();
        return spawned;
    }

    /** Call after particles were added or removed. */
    private void particlesChanged() {
        generation++;
        if (solver != null) {
            solver.invalidateBlockCaches();
        }
    }

    private static float finite(float v) {
        return Float.isFinite(v) ? v : 0f;
    }

    /** Makes room for {@code needed} particles (at most the limit), doubling the arrays as often as necessary. */
    private void ensureCapacity(int needed) {
        int capacity = capacity();
        if (needed <= capacity) {
            return;
        }
        int newCapacity = Math.max(INITIAL_CAPACITY, capacity * 2);
        while (newCapacity < needed) {
            newCapacity *= 2;
        }
        newCapacity = Math.max(needed, Math.min(newCapacity, limit));
        ParticleSoA larger = new ParticleSoA(newCapacity);
        if (particles != null) {
            larger.copyFrom(particles);
        }
        particles = larger;
        solver = new PbfSolver(larger, voxels, pool);
        marks = new boolean[newCapacity];
        candidates = new int[newCapacity];
        candidateDistances = new float[newCapacity];
    }

    /**
     * Pushes every particle within {@code radius} of a world position radially outward (inward for a negative force)
     * and wakes it up. The velocity change falls off linearly from {@code force} at the centre to 0 at the radius;
     * radius and force are clamped to {@link #MAX_IMPULSE_RADIUS} and {@link #MAX_IMPULSE_FORCE}.
     *
     * @param force velocity change at the centre, in blocks per second
     * @return the number of particles pushed
     */
    public int applyImpulse(double x, double y, double z, float radius, float force) {
        ParticleSoA p = particles;
        if (p == null || p.activeCount == 0 || !(radius > 0f) || !Float.isFinite(force)
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return 0;
        }
        float r = Math.min(radius, MAX_IMPULSE_RADIUS);
        float f = Math.max(-MAX_IMPULSE_FORCE, Math.min(force, MAX_IMPULSE_FORCE));
        float cx = (float) (x - p.originX);
        float cy = (float) (y - p.originY);
        float cz = (float) (z - p.originZ);
        float r2 = r * r;
        float invR = 1f / r;
        int pushed = 0;
        for (int i = 0; i < p.activeCount; i++) {
            float dx = p.posX[i] - cx;
            float dy = p.posY[i] - cy;
            float dz = p.posZ[i] - cz;
            float d2 = dx * dx + dy * dy + dz * dz;
            if (d2 >= r2) {
                continue;
            }
            float d = (float) Math.sqrt(d2);
            float dv = f * (1f - d * invR);
            if (d > 1.0e-4f) {
                float s = dv / d;
                p.velX[i] += dx * s;
                p.velY[i] += dy * s;
                p.velZ[i] += dz * s;
            } else {
                p.velY[i] += dv;
            }
            p.sleepTicks[i] = 0;
            pushed++;
        }
        if (pushed > 0) {
            generation++;
        }
        return pushed;
    }

    /**
     * Removes up to {@code maxCount} particles of one material from a block, e.g. after they condensed into a vanilla
     * block. If the block holds fewer, the rest come from the 26 blocks around it: a client's particles drift a little
     * from the server's, and the total mass should still match.
     *
     * @return the number of particles removed
     */
    public int removeNear(int blockX, int blockY, int blockZ, byte material, int maxCount) {
        ParticleSoA p = particles;
        if (p == null || maxCount <= 0) {
            return 0;
        }
        int rx = blockX - p.originX;
        int ry = blockY - p.originY;
        int rz = blockZ - p.originZ;
        int removed = removeAround(p, rx, ry, rz, 0, material, maxCount);
        if (removed < maxCount) {
            removed += removeAround(p, rx, ry, rz, 1, material, maxCount - removed);
        }
        if (removed > 0) {
            particlesChanged();
        }
        return removed;
    }

    /** Receives each particle {@link #removeMarked} removes: its block and material. */
    @FunctionalInterface
    public interface RemovalSink {
        void removed(int blockX, int blockY, int blockZ, byte material);
    }

    /** Picks particle {@code i} for the next {@link #removeMarked}. */
    public void mark(int i) {
        marks[i] = true;
    }

    public boolean isMarked(int i) {
        return marks != null && marks[i];
    }

    /**
     * Picks the {@code count} particles of {@code material} closest to a world position for the next
     * {@link #removeMarked}, among those within {@code maxDistance} that are not picked yet.
     *
     * @param restingOnly only consider particles that rest (see {@link #RESTING_TICKS})
     * @param allOrNothing pick nothing unless {@code count} particles qualify
     * @return the number of particles picked
     */
    public int markNearest(double x, double y, double z, byte material, int count, float maxDistance,
                           boolean restingOnly, boolean allOrNothing) {
        ParticleSoA p = particles;
        if (p == null || count <= 0) {
            return 0;
        }
        float cx = (float) (x - p.originX);
        float cy = (float) (y - p.originY);
        float cz = (float) (z - p.originZ);
        float max2 = maxDistance * maxDistance;
        int found = 0;
        for (int i = 0; i < p.activeCount; i++) {
            if (p.material[i] != material || marks[i] || restingOnly && p.sleepTicks[i] <= RESTING_TICKS) {
                continue;
            }
            float dx = p.posX[i] - cx;
            float dy = p.posY[i] - cy;
            float dz = p.posZ[i] - cz;
            float d2 = dx * dx + dy * dy + dz * dz;
            if (d2 <= max2) {
                candidates[found] = i;
                candidateDistances[found] = d2;
                found++;
            }
        }
        if (found < count && allOrNothing) {
            return 0;
        }
        int picked = Math.min(count, found);
        if (picked < found) {
            selectSmallest(picked, found);
        }
        for (int c = 0; c < picked; c++) {
            marks[candidates[c]] = true;
        }
        return picked;
    }

    /**
     * Picks up to {@code max} particles of {@code material} in one block for the next {@link #removeMarked}, among
     * those not picked yet.
     *
     * @return the number of particles picked
     */
    public int markInBlock(int blockX, int blockY, int blockZ, byte material, int max) {
        return markInBlock(blockX, blockY, blockZ, material, max, 1f);
    }

    /**
     * Like {@link #markInBlock(int, int, int, byte, int)}, but only particles in the bottom {@code maxHeight} of the
     * block (0..1).
     */
    public int markInBlock(int blockX, int blockY, int blockZ, byte material, int max, float maxHeight) {
        ParticleSoA p = particles;
        if (p == null || max <= 0) {
            return 0;
        }
        int rx = blockX - p.originX;
        int ry = blockY - p.originY;
        int rz = blockZ - p.originZ;
        float top = ry + maxHeight;
        int picked = 0;
        for (int i = 0; i < p.activeCount && picked < max; i++) {
            if (p.material[i] == material && !marks[i]
                    && (int) Math.floor(p.posX[i]) == rx
                    && (int) Math.floor(p.posY[i]) == ry
                    && (int) Math.floor(p.posZ[i]) == rz
                    && p.posY[i] < top) {
                marks[i] = true;
                picked++;
            }
        }
        return picked;
    }

    /** Particles of {@code material} within {@code maxDistance} of a world position. */
    public int countNear(double x, double y, double z, byte material, float maxDistance) {
        ParticleSoA p = particles;
        if (p == null) {
            return 0;
        }
        float cx = (float) (x - p.originX);
        float cy = (float) (y - p.originY);
        float cz = (float) (z - p.originZ);
        float max2 = maxDistance * maxDistance;
        int found = 0;
        for (int i = 0; i < p.activeCount; i++) {
            if (p.material[i] != material) {
                continue;
            }
            float dx = p.posX[i] - cx;
            float dy = p.posY[i] - cy;
            float dz = p.posZ[i] - cz;
            if (dx * dx + dy * dy + dz * dz <= max2) {
                found++;
            }
        }
        return found;
    }

    /**
     * Reorders the first {@code n} candidates so the {@code k} with the smallest distances come first
     * (quickselect, in place).
     */
    private void selectSmallest(int k, int n) {
        int lo = 0;
        int hi = n - 1;
        while (lo < hi) {
            float pivot = candidateDistances[(lo + hi) >>> 1];
            int i = lo;
            int j = hi;
            while (i <= j) {
                while (candidateDistances[i] < pivot) {
                    i++;
                }
                while (candidateDistances[j] > pivot) {
                    j--;
                }
                if (i <= j) {
                    swapCandidates(i, j);
                    i++;
                    j--;
                }
            }
            // Now [lo..j] <= pivot <= [i..hi]; continue in the part that holds index k - 1.
            if (k - 1 <= j) {
                hi = j;
            } else if (k - 1 >= i) {
                lo = i;
            } else {
                return;
            }
        }
    }

    private void swapCandidates(int a, int b) {
        int index = candidates[a];
        candidates[a] = candidates[b];
        candidates[b] = index;
        float d = candidateDistances[a];
        candidateDistances[a] = candidateDistances[b];
        candidateDistances[b] = d;
    }

    /**
     * Removes every particle picked with {@link #mark} or {@link #markNearest} and reports each one to {@code sink}
     * (may be null).
     *
     * @return the number of particles removed
     */
    public int removeMarked(RemovalSink sink) {
        ParticleSoA p = particles;
        if (p == null) {
            return 0;
        }
        int removed = 0;
        // Backwards: the particle remove() moves into slot i comes from the end, which was checked already.
        for (int i = p.activeCount - 1; i >= 0; i--) {
            if (!marks[i]) {
                continue;
            }
            if (sink != null) {
                sink.removed(p.originX + (int) Math.floor(p.posX[i]), p.originY + (int) Math.floor(p.posY[i]),
                        p.originZ + (int) Math.floor(p.posZ[i]), p.material[i]);
            }
            p.remove(i);
            marks[i] = false;
            marks[p.activeCount] = false;
            removed++;
        }
        if (removed > 0) {
            particlesChanged();
        }
        return removed;
    }

    /**
     * Particle counts per block for the current positions, rebuilt on first use after the particles changed. Null
     * while no particles are allocated.
     */
    public BlockOccupancy occupancy() {
        if (particles == null) {
            return null;
        }
        if (occupancy == null) {
            occupancy = new BlockOccupancy();
        }
        if (occupancyGeneration != generation) {
            occupancy.build(particles, RESTING_TICKS);
            occupancyGeneration = generation;
        }
        return occupancy;
    }

    /** Removes particles of {@code material} within {@code reach} blocks of origin-relative block (rx, ry, rz). */
    private static int removeAround(ParticleSoA p, int rx, int ry, int rz, int reach, byte material, int maxCount) {
        int removed = 0;
        // Backwards, so the particle that remove() moves into slot i has been checked already.
        for (int i = p.activeCount - 1; i >= 0 && removed < maxCount; i--) {
            if (p.material[i] != material
                    || Math.abs((int) Math.floor(p.posX[i]) - rx) > reach
                    || Math.abs((int) Math.floor(p.posY[i]) - ry) > reach
                    || Math.abs((int) Math.floor(p.posZ[i]) - rz) > reach) {
                continue;
            }
            p.remove(i);
            removed++;
        }
        return removed;
    }

    /**
     * Removes all particles and frees their arrays until the next spawn.
     *
     * @return the number of particles removed
     */
    public int clear() {
        int removed = activeCount();
        particles = null;
        solver = null;
        marks = null;
        candidates = null;
        candidateDistances = null;
        occupancy = null;
        generation++;
        voxels.clear();
        stats.reset();
        return removed;
    }

    /**
     * Advances one game tick: caches the world around the particles, steps the solver and removes particles that fell
     * out of the world. Call from the thread that ticks {@code level}; allocates nothing once the voxel cache holds the
     * sections the particles reach.
     */
    public void tick(Level level) {
        advance(level, minY(level) - VOID_DEPTH);
    }

    /** {@link #tick} without a level, for tests: the voxel cache must already hold the world. */
    void advance(int removeBelowY) {
        advance(null, removeBelowY);
    }

    private void advance(Level level, int removeBelowY) {
        if (activeCount() == 0) {
            return;
        }
        long start = System.nanoTime();
        long allocatedBefore = AllocationMeter.currentThreadBytes();
        long workersBefore = pool.workerAllocatedBytes();
        if (level != null) {
            voxels.ensureCached(level, particles);
        }
        long voxelsDone = System.nanoTime();
        solver.step();
        generation++;
        removeBelow(removeBelowY);
        long workerAllocated = pool.workerAllocatedBytes() - workersBefore;
        long allocated = AllocationMeter.currentThreadBytes() - allocatedBefore;
        stats.record(System.nanoTime() - start, voxelsDone - start, solver, allocated, workerAllocated);
    }

    private static int minY(Level level) {
        //? if >=1.21.2 {
        return level.getMinY();
        //?} else {
        /*return level.getMinBuildHeight();
        *///?}
    }

    private void removeBelow(int worldY) {
        ParticleSoA p = particles;
        float y = worldY - p.originY;
        int before = p.activeCount;
        for (int i = p.activeCount - 1; i >= 0; i--) {
            if (p.posY[i] < y) {
                p.remove(i);
                removedInVoid++;
            }
        }
        if (p.activeCount != before) {
            particlesChanged();
        }
    }

    /** Timings and allocations of the last simulated ticks; reset by {@link #clear()}. */
    public SimulationStats stats() {
        return stats;
    }

    /** Particles removed because they fell out of the world, since this simulation was created. */
    public long removedInVoid() {
        return removedInVoid;
    }

    /** Particles that stayed slow for more than {@link #RESTING_TICKS} ticks. */
    public int countResting() {
        ParticleSoA p = particles;
        int resting = 0;
        for (int i = 0; i < activeCount(); i++) {
            if (p.sleepTicks[i] > RESTING_TICKS) {
                resting++;
            }
        }
        return resting;
    }

    /** Writes the number of particles of each material into {@code counts} (indexed by material id). */
    public void countByMaterial(int[] counts) {
        Arrays.fill(counts, 0);
        ParticleSoA p = particles;
        for (int i = 0; i < activeCount(); i++) {
            counts[p.material[i]]++;
        }
    }
}
