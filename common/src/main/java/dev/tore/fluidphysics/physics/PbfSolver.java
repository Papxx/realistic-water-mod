// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

import static dev.tore.fluidphysics.physics.MaterialProperties.COHESION;
import static dev.tore.fluidphysics.physics.MaterialProperties.COLLISION_CLASS;
import static dev.tore.fluidphysics.physics.MaterialProperties.GRANULAR;
import static dev.tore.fluidphysics.physics.MaterialProperties.GRAVITY;
import static dev.tore.fluidphysics.physics.MaterialProperties.RESTITUTION;
import static dev.tore.fluidphysics.physics.MaterialProperties.VISCOSITY;
import static dev.tore.fluidphysics.physics.MaterialProperties.WALL_FRICTION;

import java.util.Arrays;
import net.minecraft.core.BlockPos;

/**
 * Position Based Fluids (Macklin &amp; Müller 2013) for water, lava and slime, plus position-based contacts with
 * Coulomb friction for granular sand, colliding against the {@link ChunkVoxelCache}.
 *
 * <p>Each {@link #step()} advances one game tick in {@link #SUBSTEPS} substeps. A substep predicts positions, sorts
 * them into the {@link SpatialHashGrid}, copies the particles into grid order (so neighbours sit close together in
 * memory), builds one neighbour list, resolves collisions once, runs {@link #SOLVER_ITERATIONS} constraint iterations
 * (each followed by collision), derives velocities, applies viscosity and writes the result back to the
 * {@link ParticleSoA}.
 *
 * <p>With only two iterations, a plain Jacobi PBF solver lets fluids compress and ring and lets piles creep. Three
 * things keep it stable: solid faces add density like resting particles would (no collapse at walls), corrections
 * are under-relaxed and mass-scaled by height (deep pools hold their volume and come to rest), and grains and walls
 * use position-based Coulomb friction (sand piles up, nothing creeps down a slope below the friction angle).
 *
 * <p>Every pass works on one particle at a time and only writes that particle's data (Jacobi style), so the passes
 * are split between the threads of a {@link WorkerPool}. All working memory is allocated in the constructor (about
 * 500 bytes per particle of capacity, three quarters of it neighbour lists; 16 MiB at 32,768); a step allocates
 * nothing.
 *
 * <p>Not thread-safe: call {@link #step()} from one thread at a time, and do not change the {@link ChunkVoxelCache}
 * while a step runs (its workers read the cache concurrently).
 */
public final class PbfSolver {
    public static final int SUBSTEPS = 2;
    public static final float TICK_SECONDS = 0.05f;
    public static final float DT = TICK_SECONDS / SUBSTEPS;
    public static final int SOLVER_ITERATIONS = 2;

    /** SPH smoothing radius in blocks. */
    public static final float H = 0.35f;
    public static final float H2 = H * H;
    /**
     * Target of the density constraint. One block holds 64 particles, but with only about ten neighbours inside the
     * smoothing radius the kernel sum depends on how particles are arranged: the spawn lattice sums to about 62,
     * an irregular liquid packing to more. 52 was calibrated so a resting one-block pool keeps about its volume
     * (see {@code PbfSolverTest#restingPoolsKeepTheirVolumeAndFallAsleep}).
     */
    public static final float REST_DENSITY = 52.0f;
    private static final float INV_REST_DENSITY = 1.0f / REST_DENSITY;

    public static final float POLY6_COEFF = 315.0f / (64.0f * (float) Math.PI * (float) Math.pow(H, 9));
    public static final float SPIKY_GRAD_COEFF = -45.0f / ((float) Math.PI * (float) Math.pow(H, 6));
    /** Poly6 kernel at distance 0: a particle's contribution to its own density. */
    private static final float W_SELF = POLY6_COEFF * H2 * H2 * H2;

    /** Constraint force mixing: softens the density constraint when a particle has few neighbours. */
    private static final float RELAXATION = 1.0f;
    /** Artificial pressure (s_corr): short-range repulsion that stops particles from clumping. */
    private static final float S_CORR_K = 0.001f;
    private static final float S_CORR_DQ = 0.2f * H;
    private static final float INV_W_DQ = 1.0f / (POLY6_COEFF * cube(H2 - S_CORR_DQ * S_CORR_DQ));

    /**
     * Solid sub-voxels count towards a fluid particle's density as if each held a resting particle, so fluid next to
     * a wall or floor is not under-dense (which would let it collapse). One face of solid sub-voxels is treated as a
     * plane of such particles, 16 per square block, whose poly6 sum at a distance {@code d'} from their centres
     * integrates to {@code 4 pi POLY6 (h^2 - d'^2)^4}.
     */
    private static final float BOUNDARY_COEFF = 4.0f * (float) Math.PI * POLY6_COEFF;
    /** Distance from a solid face to the centres of the sub-voxels behind it. */
    private static final float BOUNDARY_CENTER_DEPTH = ParticleSoA.PARTICLE_SPACING;

    /** Granular contact distance: one particle per 0.25-block sub-voxel. */
    private static final float GRAIN_DIAMETER = ParticleSoA.PARTICLE_SPACING;
    private static final float GRAIN_DIAMETER2 = GRAIN_DIAMETER * GRAIN_DIAMETER;
    /**
     * Contact pushes of a grain are averaged over its contacts and over-relaxed by this factor (Macklin et al. 2014,
     * "Unified Particle Physics"), but never pushed further than the overlap itself: pushing a grain past the contact
     * along a tilted normal makes it creep sideways down a slope.
     */
    private static final float GRAIN_SOR = 1.5f;
    /** Friction of a grain is averaged as if it had at most this many contacts. */
    private static final float GRAIN_FRICTION_CONTACTS = 2.0f;

    /**
     * Fraction of a fluid particle's position correction applied per iteration. The Jacobi solver overshoots when
     * every neighbour corrects at once; under-relaxing lets resting fluid come to rest instead of ringing.
     */
    private static final float FLUID_CORRECTION_SCALE = 0.5f;
    /**
     * Mass scaling for fluids (Macklin et al. 2014): in a neighbour pair, the upper particle takes up to this much
     * more of the correction and the lower one that much less. Pressure then reaches the top of a deep column within
     * the few iterations we run, so deep pools do not collapse.
     */
    private static final float FLUID_MASS_SCALING = 0.5f;

    /** Neighbours kept per particle; extra neighbours are dropped. */
    public static final int MAX_NEIGHBORS = 32;

    /**
     * Speed limit in blocks per second. A particle moves less than one 0.25-block sub-voxel per substep, so it can
     * not tunnel through a one-sub-voxel wall such as a trapdoor, and less than one block per tick, which is what
     * {@link ChunkVoxelCache#ensureCached} prepares for.
     */
    public static final float MAX_SPEED = 0.95f * ParticleSoA.PARTICLE_SPACING / DT;
    private static final float MAX_SPEED2 = MAX_SPEED * MAX_SPEED;
    /** Below this speed a particle counts as resting for {@link ParticleSoA#sleepTicks}. */
    public static final float SLEEP_SPEED = 0.1f;
    private static final float SLEEP_SPEED2 = SLEEP_SPEED * SLEEP_SPEED;
    /** Impacts slower than this do not bounce, so resting particles do not jitter. */
    private static final float BOUNCE_MIN_SPEED = 1.0f;

    /** How far past a face a particle is placed when pushed out of a solid sub-voxel. */
    private static final float SKIN = 1.0e-3f;
    private static final float EPS2 = 1.0e-12f;

    /** Per-worker slots are this many ints apart (256 bytes), so two workers never write to the same cache line. */
    private static final int WORKER_STRIDE = 64;
    /** Slots of the direct-mapped block cache per worker and collision class. */
    private static final int MASK_CACHE_SLOTS = 64;
    /** Per-worker share of the direct-mapped block cache: 64 slots per collision class, padded to 2 KiB. */
    private static final int MASK_CACHE_STRIDE = 256;

    private static final byte CONTACT_NONE = 0;
    // Faces of a sub-voxel, also used as the outward normal of a wall contact:
    // 1 = -X, 2 = +X, 3 = -Y, 4 = +Y, 5 = -Z, 6 = +Z.

    // Phases of a step, timed separately (see lastPhaseNanos). Each sums its passes over all substeps.
    /** Copy of the positions to {@code prev*}, gravity, speed limit and predicted positions. */
    public static final int PHASE_PREDICT = 0;
    /** Grid cells, counting sort and the copy into grid order. */
    public static final int PHASE_GRID = 1;
    /** Neighbour lists. */
    public static final int PHASE_NEIGHBORS = 2;
    /** World collision and wall density, plus applying each iteration's corrections (followed by collision). */
    public static final int PHASE_COLLISION = 3;
    /** Density constraint (lambdas) and position corrections of fluids and grains. */
    public static final int PHASE_DENSITY = 4;
    /** Velocities, wall bounce and XSPH viscosity. */
    public static final int PHASE_VELOCITY = 5;
    /** Copy back into particle order and sleep counters. */
    public static final int PHASE_WRITE_BACK = 6;
    public static final int PHASE_COUNT = 7;
    private static final String[] PHASE_NAMES =
            {"predict", "grid", "neighbours", "collision", "density", "velocity", "write-back"};

    private final ParticleSoA particles;
    private final ChunkVoxelCache voxels;
    private final WorkerPool pool;
    private final SpatialHashGrid grid;

    // Particle data in grid order, valid during one substep.
    private final float[] x;
    private final float[] y;
    private final float[] z;
    private final float[] ox;
    private final float[] oy;
    private final float[] oz;
    private final float[] vx;
    private final float[] vy;
    private final float[] vz;
    /** Position corrections of one iteration, then the pre-viscosity velocity. */
    private final float[] tx;
    private final float[] ty;
    private final float[] tz;
    private final float[] lam;
    /** Density and density gradient (divided by the rest density) contributed by nearby solid faces. */
    private final float[] wallDensity;
    private final float[] wallGradX;
    private final float[] wallGradY;
    private final float[] wallGradZ;
    private final byte[] mat;
    private final byte[] contact;
    /** Last block looked up for each particle and its mask, so collision rarely hits the hash map. */
    private final long[] blockKey;
    private final long[] blockMask;
    /**
     * The same, kept per particle slot between substeps and ticks (particles rarely change blocks). An entry stores
     * the block it belongs to, so a slot reused by another particle is still correct; entries are dropped when the
     * voxel cache {@link ChunkVoxelCache#version() changes}.
     */
    private final long[] slotBlockKey;
    private final long[] slotBlockMask;
    private int seenVoxelVersion;
    /**
     * Per worker and collision class: a direct-mapped cache of recent block lookups, one slot per block position
     * modulo 4 on each axis. Particles are processed in grid order, so consecutive lookups hit the same few blocks.
     */
    private final long[] maskCacheKey;
    private final long[] maskCacheValue;

    private final int[] neighbors;
    private final int[] neighborCount;
    /** Spiky kernel gradient factor of each neighbour pair, computed by the lambda pass and reused by corrections. */
    private final float[] pairGrad;
    /** Vertical component of the unit vector from each neighbour to the particle, for mass scaling. */
    private final float[] pairUp;
    /** Per worker: the largest neighbour count it saw (slot 0) and the sum of all its neighbour counts (slot 1). */
    private final int[] workerNeighborStats;

    private final WorkerPool.RangeTask predictTask = this::predict;
    private final WorkerPool.RangeTask cellsTask = this::computeCells;
    private final WorkerPool.RangeTask gatherTask = this::gather;
    private final WorkerPool.RangeTask neighborTask = this::buildNeighbors;
    private final WorkerPool.RangeTask collideTask = this::collideAndMeasureWalls;
    private final WorkerPool.RangeTask lambdaTask = this::computeLambdas;
    private final WorkerPool.RangeTask correctionTask = this::computeCorrections;
    private final WorkerPool.RangeTask applyTask = this::applyCorrectionsAndCollide;
    private final WorkerPool.RangeTask velocityTask = this::updateVelocities;
    private final WorkerPool.RangeTask viscosityTask = this::applyViscosity;
    private final WorkerPool.RangeTask scatterTask = this::scatter;
    private final WorkerPool.RangeTask sleepTask = this::updateSleep;

    private long lastStepNanos;
    private int lastMaxNeighbors;
    private float lastMeanNeighbors;
    /** Wall time of each phase in the last step; they add up to {@link #lastStepNanos}. */
    private final long[] phaseNanos = new long[PHASE_COUNT];

    /** A solver that runs on the calling thread only. */
    public PbfSolver(ParticleSoA particles, ChunkVoxelCache voxels) {
        this(particles, voxels, new WorkerPool(1));
    }

    public PbfSolver(ParticleSoA particles, ChunkVoxelCache voxels, WorkerPool pool) {
        this.particles = particles;
        this.voxels = voxels;
        this.pool = pool;
        int capacity = particles.capacity;
        grid = new SpatialHashGrid(capacity, pool.size());
        x = new float[capacity];
        y = new float[capacity];
        z = new float[capacity];
        ox = new float[capacity];
        oy = new float[capacity];
        oz = new float[capacity];
        vx = new float[capacity];
        vy = new float[capacity];
        vz = new float[capacity];
        tx = new float[capacity];
        ty = new float[capacity];
        tz = new float[capacity];
        lam = new float[capacity];
        wallDensity = new float[capacity];
        wallGradX = new float[capacity];
        wallGradY = new float[capacity];
        wallGradZ = new float[capacity];
        mat = new byte[capacity];
        contact = new byte[capacity];
        blockKey = new long[capacity];
        blockMask = new long[capacity];
        slotBlockKey = new long[capacity];
        slotBlockMask = new long[capacity];
        maskCacheKey = new long[pool.size() * MASK_CACHE_STRIDE];
        maskCacheValue = new long[pool.size() * MASK_CACHE_STRIDE];
        Arrays.fill(slotBlockKey, Long.MIN_VALUE);
        Arrays.fill(maskCacheKey, Long.MIN_VALUE);
        seenVoxelVersion = voxels.version();
        neighbors = new int[capacity * MAX_NEIGHBORS];
        neighborCount = new int[capacity];
        pairGrad = new float[capacity * MAX_NEIGHBORS];
        pairUp = new float[capacity * MAX_NEIGHBORS];
        workerNeighborStats = new int[pool.size() * WORKER_STRIDE];
    }

    public SpatialHashGrid grid() {
        return grid;
    }

    /**
     * Forgets the block masks remembered per particle slot. Call whenever particles were added or removed: a slot
     * then holds another particle, possibly of another material, and masks depend on the material's collision class.
     */
    public void invalidateBlockCaches() {
        Arrays.fill(slotBlockKey, Long.MIN_VALUE);
    }

    /** Wall time of the last {@link #step()} in nanoseconds. */
    public long lastStepNanos() {
        return lastStepNanos;
    }

    /** Most neighbours any particle had in the last substep (capped at {@link #MAX_NEIGHBORS}). */
    public int lastMaxNeighbors() {
        return lastMaxNeighbors;
    }

    /** Mean number of neighbours per particle in the last substep. */
    public float lastMeanNeighbors() {
        return lastMeanNeighbors;
    }

    /**
     * Wall time of one phase ({@code PHASE_*}) in the last {@link #step()}, summed over its substeps, in nanoseconds.
     * The phases of a step add up to {@link #lastStepNanos()}.
     */
    public long lastPhaseNanos(int phase) {
        return phaseNanos[phase];
    }

    /** Short English name of a phase ({@code PHASE_*}) for statistics output. */
    public static String phaseName(int phase) {
        return PHASE_NAMES[phase];
    }

    /**
     * Builds the grid and neighbour lists from the current positions without moving anything. For tests: afterwards
     * {@code grid().sortedIndices[k]} is the particle in grid slot {@code k}, with neighbours
     * {@link #neighborSlot(int, int)} given as grid slots.
     */
    void buildNeighborsAtCurrentPositions() {
        ParticleSoA p = particles;
        int n = p.activeCount;
        System.arraycopy(p.posX, 0, p.predX, 0, n);
        System.arraycopy(p.posY, 0, p.predY, 0, n);
        System.arraycopy(p.posZ, 0, p.predZ, 0, n);
        prepareGrid(n, System.nanoTime());
    }

    int neighborCountOfSlot(int slot) {
        return neighborCount[slot];
    }

    int neighborSlot(int slot, int c) {
        return neighbors[slot * MAX_NEIGHBORS + c];
    }

    /** Advances the simulation by one game tick. */
    public void step() {
        long start = System.nanoTime();
        Arrays.fill(phaseNanos, 0L);
        ParticleSoA p = particles;
        int n = p.activeCount;
        System.arraycopy(p.posX, 0, p.prevX, 0, n);
        System.arraycopy(p.posY, 0, p.prevY, 0, n);
        System.arraycopy(p.posZ, 0, p.prevZ, 0, n);
        if (voxels.version() != seenVoxelVersion) {
            seenVoxelVersion = voxels.version();
            Arrays.fill(slotBlockKey, Long.MIN_VALUE);
            Arrays.fill(maskCacheKey, Long.MIN_VALUE);
        }
        long time = start;
        if (n > 0) {
            for (int s = 0; s < SUBSTEPS; s++) {
                time = substep(n, time);
            }
            pool.run(sleepTask, n);
            time = lap(PHASE_WRITE_BACK, time);
        } else {
            time = lap(PHASE_PREDICT, time);
        }
        lastStepNanos = time - start;
    }

    /** One substep; {@code time} is when the previous phase ended. Returns when the last phase of this one ended. */
    private long substep(int n, long time) {
        pool.run(predictTask, n);
        time = lap(PHASE_PREDICT, time);
        time = prepareGrid(n, time);
        for (int iter = 0; iter < SOLVER_ITERATIONS; iter++) {
            pool.run(lambdaTask, n);
            pool.run(correctionTask, n);
            time = lap(PHASE_DENSITY, time);
            pool.run(applyTask, n);
            time = lap(PHASE_COLLISION, time);
        }
        pool.run(velocityTask, n);
        pool.run(viscosityTask, n);
        time = lap(PHASE_VELOCITY, time);
        pool.run(scatterTask, n);
        return lap(PHASE_WRITE_BACK, time);
    }

    /** Adds the time since {@code since} to {@code phase} and returns the current time. */
    private long lap(int phase, long since) {
        long now = System.nanoTime();
        phaseNanos[phase] += now - since;
        return now;
    }

    /**
     * Sorts the predicted positions into the grid, copies the particles into grid order, finds neighbours and resolves
     * collisions of the predicted positions. {@code time} and the result are phase boundaries, as for {@link #lap}.
     */
    private long prepareGrid(int n, long time) {
        pool.run(cellsTask, n);
        grid.sort(n, pool.sharesFor(n));
        pool.run(gatherTask, n);
        time = lap(PHASE_GRID, time);
        pool.run(neighborTask, n);
        int max = 0;
        long sum = 0L;
        for (int w = 0; w < pool.size(); w++) {
            int slot = w * WORKER_STRIDE;
            max = Math.max(max, workerNeighborStats[slot]);
            sum += workerNeighborStats[slot + 1];
            workerNeighborStats[slot] = 0;
            workerNeighborStats[slot + 1] = 0;
        }
        lastMaxNeighbors = max;
        lastMeanNeighbors = n == 0 ? 0f : (float) sum / n;
        time = lap(PHASE_NEIGHBORS, time);
        pool.run(collideTask, n);
        return lap(PHASE_COLLISION, time);
    }

    /** Applies gravity, clamps the speed and predicts positions (particle order). */
    private void predict(int from, int to, int worker) {
        ParticleSoA p = particles;
        for (int i = from; i < to; i++) {
            float vxi = p.velX[i];
            float vyi = p.velY[i] + GRAVITY[p.material[i]] * DT;
            float vzi = p.velZ[i];
            float speed2 = vxi * vxi + vyi * vyi + vzi * vzi;
            if (speed2 > MAX_SPEED2) {
                float scale = MAX_SPEED / (float) Math.sqrt(speed2);
                vxi *= scale;
                vyi *= scale;
                vzi *= scale;
            }
            p.velX[i] = vxi;
            p.velY[i] = vyi;
            p.velZ[i] = vzi;
            p.predX[i] = p.posX[i] + vxi * DT;
            p.predY[i] = p.posY[i] + vyi * DT;
            p.predZ[i] = p.posZ[i] + vzi * DT;
        }
    }

    private void computeCells(int from, int to, int worker) {
        grid.computeCells(particles.predX, particles.predY, particles.predZ, from, to, worker);
    }

    /** Copies the particles into grid order. */
    private void gather(int from, int to, int worker) {
        ParticleSoA p = particles;
        int[] order = grid.sortedIndices;
        for (int k = from; k < to; k++) {
            int i = order[k];
            x[k] = p.predX[i];
            y[k] = p.predY[i];
            z[k] = p.predZ[i];
            ox[k] = p.posX[i];
            oy[k] = p.posY[i];
            oz[k] = p.posZ[i];
            vx[k] = p.velX[i];
            vy[k] = p.velY[i];
            vz[k] = p.velZ[i];
            mat[k] = p.material[i];
            contact[k] = CONTACT_NONE;
            blockKey[k] = slotBlockKey[i];
            blockMask[k] = slotBlockMask[i];
        }
    }

    /** Writes positions, velocities, lambdas and neighbour counts back to the particles (particle order). */
    private void scatter(int from, int to, int worker) {
        ParticleSoA p = particles;
        int[] order = grid.sortedIndices;
        for (int k = from; k < to; k++) {
            int i = order[k];
            p.posX[i] = x[k];
            p.posY[i] = y[k];
            p.posZ[i] = z[k];
            p.velX[i] = vx[k];
            p.velY[i] = vy[k];
            p.velZ[i] = vz[k];
            p.lambda[i] = lam[k];
            p.neighbors[i] = (byte) neighborCount[k];
            slotBlockKey[i] = blockKey[k];
            slotBlockMask[i] = blockMask[k];
        }
    }

    /**
     * Resolves collisions of the predicted positions before the first iteration, so contacts and friction already see
     * particles resting on the floor, then measures the wall density at the resolved positions.
     */
    private void collideAndMeasureWalls(int from, int to, int worker) {
        for (int k = from; k < to; k++) {
            collide(k, worker);
            computeWallDensity(k, worker);
        }
    }

    /**
     * Finds every particle within {@link #H} of each particle, using the 3 x 3 x 3 grid cells around it.
     *
     * <p>The three cells of a row along x have consecutive buckets (see {@link SpatialHashGrid}), so their particles
     * form one range of the sorted array and a neighbourhood is scanned as nine ranges. The 27 buckets of a
     * neighbourhood are always distinct, so no particle is found twice, and particles of far-away cells that share a
     * bucket fail the distance test.
     */
    private void buildNeighbors(int from, int to, int worker) {
        int[] cellStart = grid.cellStart;
        int[] cellCount = grid.cellCount;
        int maxCount = workerNeighborStats[worker * WORKER_STRIDE];
        int sum = 0;
        for (int k = from; k < to; k++) {
            float xk = x[k];
            float yk = y[k];
            float zk = z[k];
            int cx = SpatialHashGrid.cellCoord(xk);
            int cy = SpatialHashGrid.cellCoord(yk);
            int cz = SpatialHashGrid.cellCoord(zk);
            int base = k * MAX_NEIGHBORS;
            int count = 0;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int first = grid.hashCell(cx - 1, cy + dy, cz + dz);
                    int last = grid.hashCell(cx + 1, cy + dy, cz + dz);
                    if (last == first + 2) {
                        count = scanRange(k, xk, yk, zk, cellStart[first], cellStart[last] + cellCount[last],
                                base, count);
                    } else {
                        // The row wraps around the edge of the bucket table: scan its three buckets separately.
                        int middle = grid.hashCell(cx, cy + dy, cz + dz);
                        count = scanRange(k, xk, yk, zk, cellStart[first], cellStart[first] + cellCount[first],
                                base, count);
                        count = scanRange(k, xk, yk, zk, cellStart[middle], cellStart[middle] + cellCount[middle],
                                base, count);
                        count = scanRange(k, xk, yk, zk, cellStart[last], cellStart[last] + cellCount[last],
                                base, count);
                    }
                }
            }
            neighborCount[k] = count;
            sum += count;
            if (count > maxCount) {
                maxCount = count;
            }
        }
        workerNeighborStats[worker * WORKER_STRIDE] = maxCount;
        workerNeighborStats[worker * WORKER_STRIDE + 1] += sum;
    }

    /** Appends the particles of sorted slots {@code start..end-1} within {@link #H} of particle {@code k}. */
    private int scanRange(int k, float xk, float yk, float zk, int start, int end, int base, int count) {
        for (int m = start; m < end; m++) {
            float dx = xk - x[m];
            float dy = yk - y[m];
            float dz = zk - z[m];
            // Branch-free: always write the candidate, count it only if it is a neighbour. At the cap the last slot
            // keeps being overwritten; every pass re-checks the distance anyway.
            boolean hit = (dx * dx + dy * dy + dz * dz < H2) & (m != k);
            neighbors[base + Math.min(count, MAX_NEIGHBORS - 1)] = m;
            count = Math.min(count + (hit ? 1 : 0), MAX_NEIGHBORS);
        }
        return count;
    }

    /** Density and its gradient contributed by solid faces of the six sub-voxels next to fluid particle {@code k}. */
    private void computeWallDensity(int k, int worker) {
        float density = 0f;
        float gx = 0f;
        float gy = 0f;
        float gz = 0f;
        if (!GRANULAR[mat[k]]) {
            float px = x[k];
            float py = y[k];
            float pz = z[k];
            int lbx = (int) Math.floor(px);
            int lby = (int) Math.floor(py);
            int lbz = (int) Math.floor(pz);
            int wx = particles.originX + lbx;
            int wy = particles.originY + lby;
            int wz = particles.originZ + lbz;
            int cls = COLLISION_CLASS[mat[k]];
            long mask = blockMaskOf(k, wx, wy, wz, cls, worker);
            float fx = (px - lbx) * 4f;
            float fy = (py - lby) * 4f;
            float fz = (pz - lbz) * 4f;
            int sx = Math.min((int) fx, 3);
            int sy = Math.min((int) fy, 3);
            int sz = Math.min((int) fz, 3);
            // In an empty block, a sub-voxel away from the block faces has only free neighbours.
            boolean interior = sx > 0 && sx < 3 && sy > 0 && sy < 3 && sz > 0 && sz < 3;
            if (!(interior && mask == ChunkVoxelCache.EMPTY) && !ChunkVoxelCache.isSolid(mask, sx, sy, sz)) {
                float ux = fx - sx;
                float uy = fy - sy;
                float uz = fz - sz;
                // For each solid face: density, and the gradient pointing towards the face.
                float d;
                if (!faceOpen(1, wx, wy, wz, mask, sx, sy, sz, cls, worker)) {
                    d = wallDistance(ux * 0.25f);
                    density += wallDensityAt(d);
                    gx -= wallGradientAt(d);
                }
                if (!faceOpen(2, wx, wy, wz, mask, sx, sy, sz, cls, worker)) {
                    d = wallDistance((1f - ux) * 0.25f);
                    density += wallDensityAt(d);
                    gx += wallGradientAt(d);
                }
                if (!faceOpen(3, wx, wy, wz, mask, sx, sy, sz, cls, worker)) {
                    d = wallDistance(uy * 0.25f);
                    density += wallDensityAt(d);
                    gy -= wallGradientAt(d);
                }
                if (!faceOpen(4, wx, wy, wz, mask, sx, sy, sz, cls, worker)) {
                    d = wallDistance((1f - uy) * 0.25f);
                    density += wallDensityAt(d);
                    gy += wallGradientAt(d);
                }
                if (!faceOpen(5, wx, wy, wz, mask, sx, sy, sz, cls, worker)) {
                    d = wallDistance(uz * 0.25f);
                    density += wallDensityAt(d);
                    gz -= wallGradientAt(d);
                }
                if (!faceOpen(6, wx, wy, wz, mask, sx, sy, sz, cls, worker)) {
                    d = wallDistance((1f - uz) * 0.25f);
                    density += wallDensityAt(d);
                    gz += wallGradientAt(d);
                }
            }
        }
        wallDensity[k] = density;
        wallGradX[k] = gx * INV_REST_DENSITY;
        wallGradY[k] = gy * INV_REST_DENSITY;
        wallGradZ[k] = gz * INV_REST_DENSITY;
    }

    /** Distance from a particle to the plane of sub-voxel centres behind a face that is {@code faceDistance} away. */
    private static float wallDistance(float faceDistance) {
        return Math.min(faceDistance + BOUNDARY_CENTER_DEPTH, H);
    }

    private static float wallDensityAt(float d) {
        float q = H2 - d * d;
        return BOUNDARY_COEFF * q * q * q * q;
    }

    /** How fast the wall density grows when moving towards the wall. */
    private static float wallGradientAt(float d) {
        float q = H2 - d * d;
        return 8.0f * BOUNDARY_COEFF * d * q * q * q;
    }

    /** PBF: density constraint multiplier of every fluid particle. */
    private void computeLambdas(int from, int to, int worker) {
        for (int k = from; k < to; k++) {
            byte m = mat[k];
            if (GRANULAR[m]) {
                lam[k] = 0f;
                continue;
            }
            float xk = x[k];
            float yk = y[k];
            float zk = z[k];
            float density = W_SELF + wallDensity[k];
            float gx = wallGradX[k];
            float gy = wallGradY[k];
            float gz = wallGradZ[k];
            float gradSum2 = 0f;
            int base = k * MAX_NEIGHBORS;
            int count = neighborCount[k];
            for (int c = 0; c < count; c++) {
                int j = neighbors[base + c];
                float dx = xk - x[j];
                float dy = yk - y[j];
                float dz = zk - z[j];
                float r2 = dx * dx + dy * dy + dz * dz;
                float w = 0f;
                float up = 0f;
                if (r2 < H2) {
                    float q = H2 - r2;
                    density += POLY6_COEFF * q * q * q;
                    if (r2 > EPS2) {
                        float r = (float) Math.sqrt(r2);
                        float invR = 1.0f / r;
                        float hr = H - r;
                        // Gradient of C_k with respect to particle j is -w * d, with respect to k it is +w * d.
                        w = SPIKY_GRAD_COEFF * INV_REST_DENSITY * hr * hr * invR;
                        up = dy * invR;
                        // Neighbour j moves (1 - scaling * up) times as much as an unscaled particle would.
                        gradSum2 += (1f - FLUID_MASS_SCALING * up) * w * w * r2;
                        gx += w * dx;
                        gy += w * dy;
                        gz += w * dz;
                    }
                }
                pairGrad[base + c] = w;
                pairUp[base + c] = up;
            }
            float constraint = density * INV_REST_DENSITY - 1.0f;
            if (constraint < 0f) {
                constraint *= COHESION[m];
            }
            gradSum2 += gx * gx + gy * gy + gz * gz;
            lam[k] = -constraint / (gradSum2 + RELAXATION);
        }
    }

    /** Position corrections of one iteration: density constraint for fluids, contacts and friction for grains. */
    private void computeCorrections(int from, int to, int worker) {
        for (int k = from; k < to; k++) {
            if (GRANULAR[mat[k]]) {
                grainCorrection(k);
            } else {
                fluidCorrection(k);
            }
        }
    }

    private void fluidCorrection(int k) {
        float xk = x[k];
        float yk = y[k];
        float zk = z[k];
        float lk = lam[k];
        // Solid faces push like resting particles would (their lambda counted as zero).
        float cx = lk * wallGradX[k];
        float cy = lk * wallGradY[k];
        float cz = lk * wallGradZ[k];
        int base = k * MAX_NEIGHBORS;
        int count = neighborCount[k];
        for (int c = 0; c < count; c++) {
            int j = neighbors[base + c];
            float dx = xk - x[j];
            float dy = yk - y[j];
            float dz = zk - z[j];
            float r2 = dx * dx + dy * dy + dz * dz;
            if (r2 >= H2) {
                continue;
            }
            if (r2 <= EPS2) {
                // Two particles on the same spot: no gradient direction, so split them along x.
                cx += k > j ? 1.0e-3f : -1.0e-3f;
                continue;
            }
            float q = H2 - r2;
            float wRatio = POLY6_COEFF * q * q * q * INV_W_DQ;
            float wRatio2 = wRatio * wRatio;
            float sCorr = -S_CORR_K * wRatio2 * wRatio2;
            // j's constraint moves k more when k is the upper particle of the pair (mass scaling).
            float jShare = 1f + FLUID_MASS_SCALING * pairUp[base + c];
            float s = (lk + jShare * lam[j] + sCorr) * pairGrad[base + c];
            cx += s * dx;
            cy += s * dy;
            cz += s * dz;
        }
        tx[k] = cx * FLUID_CORRECTION_SCALE;
        ty[k] = cy * FLUID_CORRECTION_SCALE;
        tz[k] = cz * FLUID_CORRECTION_SCALE;
    }

    /**
     * Grains push overlapping grains apart and cancel relative sliding with Coulomb friction: sliding smaller than
     * the static limit is removed completely, larger sliding is reduced by the kinetic limit. Both limits scale with
     * the overlap, so a slope holds when its angle stays below atan(friction).
     */
    private void grainCorrection(int k) {
        float xk = x[k];
        float yk = y[k];
        float zk = z[k];
        float moveX = xk - ox[k];
        float moveY = yk - oy[k];
        float moveZ = zk - oz[k];
        float staticFriction = WALL_FRICTION[mat[k]];
        float kineticFriction = 0.7f * staticFriction;
        float pushX = 0f;
        float pushY = 0f;
        float pushZ = 0f;
        float frictionX = 0f;
        float frictionY = 0f;
        float frictionZ = 0f;
        int contacts = 0;
        int base = k * MAX_NEIGHBORS;
        int count = neighborCount[k];
        for (int c = 0; c < count; c++) {
            int j = neighbors[base + c];
            if (!GRANULAR[mat[j]]) {
                continue;
            }
            float dx = xk - x[j];
            float dy = yk - y[j];
            float dz = zk - z[j];
            float r2 = dx * dx + dy * dy + dz * dz;
            if (r2 >= GRAIN_DIAMETER2) {
                continue;
            }
            float nx;
            float ny;
            float nz;
            float r;
            if (r2 > EPS2) {
                r = (float) Math.sqrt(r2);
                float inv = 1.0f / r;
                nx = dx * inv;
                ny = dy * inv;
                nz = dz * inv;
            } else {
                r = 0f;
                nx = k > j ? 1f : -1f;
                ny = 0f;
                nz = 0f;
            }
            float overlap = GRAIN_DIAMETER - r;
            // Share of the pair's correction taken by k: all of it if k rests on top of j, none if j rests on k,
            // half for side by side. Lower grains act heavier, which keeps piles stable with few iterations
            // (local form of the mass scaling in Macklin et al. 2014).
            float share = 0.5f + 0.5f * ny;
            float push = share * overlap;
            pushX += push * nx;
            pushY += push * ny;
            pushZ += push * nz;
            // Sliding of k relative to j during this substep, without the normal part.
            float relX = moveX - (x[j] - ox[j]);
            float relY = moveY - (y[j] - oy[j]);
            float relZ = moveZ - (z[j] - oz[j]);
            float relN = relX * nx + relY * ny + relZ * nz;
            float tanX = relX - relN * nx;
            float tanY = relY - relN * ny;
            float tanZ = relZ - relN * nz;
            float tan2 = tanX * tanX + tanY * tanY + tanZ * tanZ;
            if (tan2 > EPS2) {
                float tanLen = (float) Math.sqrt(tan2);
                float removed = tanLen < staticFriction * overlap
                        ? 1f : Math.min(kineticFriction * overlap / tanLen, 1f);
                frictionX -= share * removed * tanX;
                frictionY -= share * removed * tanY;
                frictionZ -= share * removed * tanZ;
            }
            contacts++;
        }
        if (contacts > 0) {
            float pushScale = Math.min(1f, GRAIN_SOR / contacts);
            float frictionScale = Math.min(1f, GRAIN_FRICTION_CONTACTS / contacts);
            tx[k] = pushX * pushScale + frictionX * frictionScale;
            ty[k] = pushY * pushScale + frictionY * frictionScale;
            tz[k] = pushZ * pushScale + frictionZ * frictionScale;
        } else {
            tx[k] = 0f;
            ty[k] = 0f;
            tz[k] = 0f;
        }
    }

    private void applyCorrectionsAndCollide(int from, int to, int worker) {
        for (int k = from; k < to; k++) {
            x[k] += tx[k];
            y[k] += ty[k];
            z[k] += tz[k];
            collide(k, worker);
        }
    }

    /** Sub-voxel mask of a block for collision class {@code cls}, through the worker's direct-mapped cache. */
    private long cachedMask(int worker, int cls, int wx, int wy, int wz) {
        long key = BlockPos.asLong(wx, wy, wz);
        int slot = worker * MASK_CACHE_STRIDE + cls * MASK_CACHE_SLOTS
                + ((wx & 3) | ((wy & 3) << 2) | ((wz & 3) << 4));
        if (maskCacheKey[slot] == key) {
            return maskCacheValue[slot];
        }
        long mask = voxels.getMask(wx, wy, wz, cls);
        maskCacheKey[slot] = key;
        maskCacheValue[slot] = mask;
        return mask;
    }

    /**
     * Sub-voxel mask of block ({@code wx}, {@code wy}, {@code wz}) for particle {@code k}'s collision class
     * {@code cls}, remembered per particle.
     */
    private long blockMaskOf(int k, int wx, int wy, int wz, int cls, int worker) {
        long key = BlockPos.asLong(wx, wy, wz);
        if (key == blockKey[k]) {
            return blockMask[k];
        }
        long mask = cachedMask(worker, cls, wx, wy, wz);
        blockKey[k] = key;
        blockMask[k] = mask;
        return mask;
    }

    /**
     * Pushes particle {@code k} out of solid world geometry. It leaves through the face it entered by (found by
     * sweeping from its position at the start of the substep), or through the nearest open face if that is blocked.
     */
    private void collide(int k, int worker) {
        float px = x[k];
        float py = y[k];
        float pz = z[k];
        int lbx = (int) Math.floor(px);
        int lby = (int) Math.floor(py);
        int lbz = (int) Math.floor(pz);
        int wx = particles.originX + lbx;
        int wy = particles.originY + lby;
        int wz = particles.originZ + lbz;
        int cls = COLLISION_CLASS[mat[k]];
        long mask = blockMaskOf(k, wx, wy, wz, cls, worker);
        if (mask == ChunkVoxelCache.EMPTY) {
            return;
        }
        // Position in sub-voxel units within the block, 0..4.
        float fx = (px - lbx) * 4f;
        float fy = (py - lby) * 4f;
        float fz = (pz - lbz) * 4f;
        int sx = Math.min((int) fx, 3);
        int sy = Math.min((int) fy, 3);
        int sz = Math.min((int) fz, 3);
        if (!ChunkVoxelCache.isSolid(mask, sx, sy, sz)) {
            return;
        }
        // Position within the solid sub-voxel (0..1 per axis), now and at the start of the substep.
        float ux = fx - sx;
        float uy = fy - sy;
        float uz = fz - sz;
        float oux = (ox[k] - lbx) * 4f - sx;
        float ouy = (oy[k] - lby) * 4f - sy;
        float ouz = (oz[k] - lbz) * 4f - sz;

        // The entry face is the one crossed last on the way in.
        int face = 0;
        float entryTime = -1f;
        if (oux < 0f) {
            face = 1;
            entryTime = -oux / (ux - oux);
        } else if (oux > 1f) {
            face = 2;
            entryTime = (oux - 1f) / (oux - ux);
        }
        if (ouy < 0f) {
            float t = -ouy / (uy - ouy);
            if (t > entryTime) {
                face = 3;
                entryTime = t;
            }
        } else if (ouy > 1f) {
            float t = (ouy - 1f) / (ouy - uy);
            if (t > entryTime) {
                face = 4;
                entryTime = t;
            }
        }
        if (ouz < 0f) {
            float t = -ouz / (uz - ouz);
            if (t > entryTime) {
                face = 5;
            }
        } else if (ouz > 1f) {
            float t = (ouz - 1f) / (ouz - uz);
            if (t > entryTime) {
                face = 6;
            }
        }
        if (face == 0 || !faceOpen(face, wx, wy, wz, mask, sx, sy, sz, cls, worker)) {
            face = nearestOpenFace(wx, wy, wz, mask, sx, sy, sz, ux, uy, uz, cls, worker);
        }
        float friction = WALL_FRICTION[mat[k]];
        switch (face) {
            case 1 -> {
                x[k] = lbx + sx * 0.25f - SKIN;
                wallFriction(k, px - x[k], friction, y, oy, z, oz);
            }
            case 2 -> {
                x[k] = lbx + (sx + 1) * 0.25f + SKIN;
                wallFriction(k, x[k] - px, friction, y, oy, z, oz);
            }
            case 3 -> {
                y[k] = lby + sy * 0.25f - SKIN;
                wallFriction(k, py - y[k], friction, x, ox, z, oz);
            }
            case 4 -> {
                y[k] = lby + (sy + 1) * 0.25f + SKIN;
                wallFriction(k, y[k] - py, friction, x, ox, z, oz);
            }
            case 5 -> {
                z[k] = lbz + sz * 0.25f - SKIN;
                wallFriction(k, pz - z[k], friction, x, ox, y, oy);
            }
            case 6 -> {
                z[k] = lbz + (sz + 1) * 0.25f + SKIN;
                wallFriction(k, z[k] - pz, friction, x, ox, y, oy);
            }
            default -> {
                // Buried in solid geometry (e.g. a block was placed on it): stay where the substep started.
                x[k] = ox[k];
                y[k] = oy[k];
                z[k] = oz[k];
                return;
            }
        }
        contact[k] = (byte) face;
    }

    /**
     * Coulomb friction against a wall: the particle's sliding along the wall during this substep (axes {@code a} and
     * {@code b}) is cancelled while it is below {@code friction} times the penetration depth (static friction), and
     * reduced by 70 % of that limit otherwise (kinetic friction).
     */
    private static void wallFriction(int k, float penetration, float friction,
                                     float[] a, float[] oa, float[] b, float[] ob) {
        float slideA = a[k] - oa[k];
        float slideB = b[k] - ob[k];
        float slide2 = slideA * slideA + slideB * slideB;
        if (slide2 <= EPS2) {
            return;
        }
        float slide = (float) Math.sqrt(slide2);
        float limit = friction * penetration;
        float removed = slide < limit ? 1f : Math.min(0.7f * limit / slide, 1f);
        a[k] -= removed * slideA;
        b[k] -= removed * slideB;
    }

    private int nearestOpenFace(int wx, int wy, int wz, long mask, int sx, int sy, int sz,
                                float ux, float uy, float uz, int cls, int worker) {
        int face = 0;
        float best = Float.MAX_VALUE;
        if (ux < best && faceOpen(1, wx, wy, wz, mask, sx, sy, sz, cls, worker)) {
            face = 1;
            best = ux;
        }
        if (1f - ux < best && faceOpen(2, wx, wy, wz, mask, sx, sy, sz, cls, worker)) {
            face = 2;
            best = 1f - ux;
        }
        if (uy < best && faceOpen(3, wx, wy, wz, mask, sx, sy, sz, cls, worker)) {
            face = 3;
            best = uy;
        }
        if (1f - uy < best && faceOpen(4, wx, wy, wz, mask, sx, sy, sz, cls, worker)) {
            face = 4;
            best = 1f - uy;
        }
        if (uz < best && faceOpen(5, wx, wy, wz, mask, sx, sy, sz, cls, worker)) {
            face = 5;
            best = uz;
        }
        if (1f - uz < best && faceOpen(6, wx, wy, wz, mask, sx, sy, sz, cls, worker)) {
            face = 6;
        }
        return face;
    }

    /** Whether the sub-voxel on the other side of {@code face} is free, looking into the next block if needed. */
    private boolean faceOpen(int face, int wx, int wy, int wz, long mask, int sx, int sy, int sz, int cls,
                             int worker) {
        switch (face) {
            case 1 -> sx--;
            case 2 -> sx++;
            case 3 -> sy--;
            case 4 -> sy++;
            case 5 -> sz--;
            default -> sz++;
        }
        if (sx < 0) {
            sx = 3;
            mask = cachedMask(worker, cls, wx - 1, wy, wz);
        } else if (sx > 3) {
            sx = 0;
            mask = cachedMask(worker, cls, wx + 1, wy, wz);
        } else if (sy < 0) {
            sy = 3;
            mask = cachedMask(worker, cls, wx, wy - 1, wz);
        } else if (sy > 3) {
            sy = 0;
            mask = cachedMask(worker, cls, wx, wy + 1, wz);
        } else if (sz < 0) {
            sz = 3;
            mask = cachedMask(worker, cls, wx, wy, wz - 1);
        } else if (sz > 3) {
            sz = 0;
            mask = cachedMask(worker, cls, wx, wy, wz + 1);
        }
        return !ChunkVoxelCache.isSolid(mask, sx, sy, sz);
    }

    /**
     * Velocity from the position change, then wall response: tangential friction and, for hard impacts, a bounce
     * that keeps {@code restitution} of the incoming normal speed. The result goes to {@link #tx} for viscosity.
     */
    private void updateVelocities(int from, int to, int worker) {
        final float invDt = 1.0f / DT;
        for (int k = from; k < to; k++) {
            float nvx = (x[k] - ox[k]) * invDt;
            float nvy = (y[k] - oy[k]) * invDt;
            float nvz = (z[k] - oz[k]) * invDt;
            int face = contact[k];
            if (face != CONTACT_NONE) {
                float restitution = RESTITUTION[mat[k]];
                // Normal axis: 0 = x, 1 = y, 2 = z; sign: +1 if the surface faces the positive direction.
                int axis = (face - 1) >> 1;
                float sign = (face & 1) == 0 ? 1f : -1f;
                if (axis == 0) {
                    nvx = bounce(nvx, vx[k], sign, restitution);
                } else if (axis == 1) {
                    nvy = bounce(nvy, vy[k], sign, restitution);
                } else {
                    nvz = bounce(nvz, vz[k], sign, restitution);
                }
            }
            tx[k] = nvx;
            ty[k] = nvy;
            tz[k] = nvz;
        }
    }

    /** Normal velocity after a wall hit; {@code incoming} is the velocity before the substep. */
    private static float bounce(float current, float incoming, float sign, float restitution) {
        float in = incoming * sign;
        if (in < -BOUNCE_MIN_SPEED) {
            float out = -restitution * in;
            if (current * sign < out) {
                return out * sign;
            }
        }
        return current;
    }

    /** XSPH viscosity: blends each velocity towards the kernel-weighted mean of its neighbours. */
    private void applyViscosity(int from, int to, int worker) {
        for (int k = from; k < to; k++) {
            float xk = x[k];
            float yk = y[k];
            float zk = z[k];
            float weightSum = 0f;
            float sumX = 0f;
            float sumY = 0f;
            float sumZ = 0f;
            int base = k * MAX_NEIGHBORS;
            int count = neighborCount[k];
            for (int c = 0; c < count; c++) {
                int j = neighbors[base + c];
                float dx = xk - x[j];
                float dy = yk - y[j];
                float dz = zk - z[j];
                float r2 = dx * dx + dy * dy + dz * dz;
                if (r2 >= H2) {
                    continue;
                }
                float q = H2 - r2;
                float w = q * q * q;
                weightSum += w;
                sumX += w * tx[j];
                sumY += w * ty[j];
                sumZ += w * tz[j];
            }
            float nvx = tx[k];
            float nvy = ty[k];
            float nvz = tz[k];
            if (weightSum > 0f) {
                float c = VISCOSITY[mat[k]];
                float inv = 1.0f / weightSum;
                nvx += c * (sumX * inv - nvx);
                nvy += c * (sumY * inv - nvy);
                nvz += c * (sumZ * inv - nvz);
            }
            float speed2 = nvx * nvx + nvy * nvy + nvz * nvz;
            if (speed2 > MAX_SPEED2) {
                float scale = MAX_SPEED / (float) Math.sqrt(speed2);
                nvx *= scale;
                nvy *= scale;
                nvz *= scale;
            }
            vx[k] = nvx;
            vy[k] = nvy;
            vz[k] = nvz;
        }
    }

    private void updateSleep(int from, int to, int worker) {
        ParticleSoA p = particles;
        for (int i = from; i < to; i++) {
            float speed2 = p.velX[i] * p.velX[i] + p.velY[i] * p.velY[i] + p.velZ[i] * p.velZ[i];
            if (speed2 < SLEEP_SPEED2) {
                if (p.sleepTicks[i] < Short.MAX_VALUE) {
                    p.sleepTicks[i]++;
                }
            } else {
                p.sleepTicks[i] = 0;
            }
        }
    }

    private static float cube(float v) {
        return v * v * v;
    }
}
