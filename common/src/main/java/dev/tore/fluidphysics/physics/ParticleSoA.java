// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

/**
 * All simulated particles, stored as a Structure of Arrays. No particle is ever an object.
 *
 * <p>Positions are stored relative to an integer block origin ({@link #originX}, {@link #originY},
 * {@link #originZ}) so floats keep sub-millimetre precision far away from the world origin. A float has
 * about 0.0005 blocks of precision at 4096 blocks from the origin, so keep particles within that range of
 * it (see {@link #rebase(int, int, int)}).
 *
 * <p>Not thread-safe: one simulation thread owns an instance.
 */
public final class ParticleSoA {
    /** Hard upper bound for the array size. */
    public static final int MAX_PARTICLES = 32768;

    public static final byte MATERIAL_WATER = 0;
    public static final byte MATERIAL_LAVA = 1;
    public static final byte MATERIAL_SLIME = 2;
    public static final byte MATERIAL_SAND = 3;
    public static final int MATERIAL_COUNT = 4;

    /** One full block or bucket holds 4 x 4 x 4 particles. */
    public static final int PARTICLES_PER_BLOCK = 64;
    /** Edge length of one particle's share of a block, and of one collision sub-voxel. */
    public static final float PARTICLE_SPACING = 0.25f;
    /** {@link #neighbors} value of a particle that has not been stepped yet. */
    public static final byte NEIGHBORS_UNKNOWN = -1;

    /** Array size of this instance, at most {@link #MAX_PARTICLES}. */
    public final int capacity;

    public final float[] posX;
    public final float[] posY;
    public final float[] posZ;
    /** Position at the start of the current tick, for render interpolation. */
    public final float[] prevX;
    public final float[] prevY;
    public final float[] prevZ;
    /** Predicted position of the current substep (solver scratch, valid only during a step). */
    public final float[] predX;
    public final float[] predY;
    public final float[] predZ;
    /** Velocity in blocks per second. */
    public final float[] velX;
    public final float[] velY;
    public final float[] velZ;
    /** PBF density constraint multiplier (solver output, kept for debugging and stats). */
    public final float[] lambda;
    public final byte[] material;
    /** Consecutive ticks the particle moved slower than the sleep threshold. */
    public final short[] sleepTicks;
    /**
     * Neighbours within the smoothing radius in the last substep (at most {@link PbfSolver#MAX_NEIGHBORS}), or
     * {@link #NEIGHBORS_UNKNOWN} until the particle's first step. Solver output, read by the renderer to find spray.
     */
    public final byte[] neighbors;

    public int activeCount;
    /** Runtime limit set by {@code /fluidphysics limit}, at most {@link #capacity}. */
    private int maxParticles;

    public int originX;
    public int originY;
    public int originZ;

    public ParticleSoA() {
        this(MAX_PARTICLES);
    }

    /** Allocates arrays for {@code capacity} particles; size this by the runtime limit, not by the maximum. */
    public ParticleSoA(int capacity) {
        if (capacity <= 0 || capacity > MAX_PARTICLES) {
            throw new IllegalArgumentException("capacity must be in 1.." + MAX_PARTICLES + ", got " + capacity);
        }
        this.capacity = capacity;
        this.maxParticles = capacity;
        posX = new float[capacity];
        posY = new float[capacity];
        posZ = new float[capacity];
        prevX = new float[capacity];
        prevY = new float[capacity];
        prevZ = new float[capacity];
        predX = new float[capacity];
        predY = new float[capacity];
        predZ = new float[capacity];
        velX = new float[capacity];
        velY = new float[capacity];
        velZ = new float[capacity];
        lambda = new float[capacity];
        material = new byte[capacity];
        sleepTicks = new short[capacity];
        neighbors = new byte[capacity];
    }

    public int maxParticles() {
        return maxParticles;
    }

    /** Sets the runtime limit, clamped to {@code 0..capacity}. Particles above a lowered limit are removed. */
    public void setMaxParticles(int limit) {
        maxParticles = Math.max(0, Math.min(limit, capacity));
        if (activeCount > maxParticles) {
            activeCount = maxParticles;
        }
    }

    public int freeSlots() {
        return maxParticles - activeCount;
    }

    /**
     * Adds a particle at a position relative to the origin.
     *
     * @return the new particle's index, or -1 if the runtime limit is reached
     */
    public int add(float x, float y, float z, float vx, float vy, float vz, byte mat) {
        if (activeCount >= maxParticles) {
            return -1;
        }
        int i = activeCount++;
        posX[i] = x;
        posY[i] = y;
        posZ[i] = z;
        prevX[i] = x;
        prevY[i] = y;
        prevZ[i] = z;
        predX[i] = x;
        predY[i] = y;
        predZ[i] = z;
        velX[i] = vx;
        velY[i] = vy;
        velZ[i] = vz;
        lambda[i] = 0f;
        material[i] = mat;
        sleepTicks[i] = 0;
        neighbors[i] = NEIGHBORS_UNKNOWN;
        return i;
    }

    /**
     * Spawns up to {@code count} (at most 64) particles on the 4 x 4 x 4 micro-grid of the world block
     * ({@code blockX}, {@code blockY}, {@code blockZ}). Each particle gets a tiny deterministic offset so a
     * freshly spawned block does not sit in a perfectly symmetric lattice.
     *
     * @return the number of particles actually spawned (less than {@code count} when the limit is reached)
     */
    public int spawnBlock(int blockX, int blockY, int blockZ, byte mat, int count, float vx, float vy, float vz) {
        int n = Math.min(count, PARTICLES_PER_BLOCK);
        float baseX = blockX - originX;
        float baseY = blockY - originY;
        float baseZ = blockZ - originZ;
        int spawned = 0;
        for (int s = 0; s < n; s++) {
            int sx = s & 3;
            int sz = (s >> 2) & 3;
            int sy = s >> 4;
            int seed = s * 0x9E3779B1 ^ blockX * 0x85EBCA6B ^ blockY * 0xC2B2AE35 ^ blockZ * 0x27D4EB2F;
            float jx = jitter(seed);
            float jy = jitter(seed * 0x2545F491);
            float jz = jitter(seed * 0x5851F42D);
            float x = baseX + (sx + 0.5f + jx) * PARTICLE_SPACING;
            float y = baseY + (sy + 0.5f + jy) * PARTICLE_SPACING;
            float z = baseZ + (sz + 0.5f + jz) * PARTICLE_SPACING;
            if (add(x, y, z, vx, vy, vz, mat) < 0) {
                break;
            }
            spawned++;
        }
        return spawned;
    }

    /** Pseudo-random offset in -0.05..0.05 of a sub-voxel from an integer seed. */
    private static float jitter(int seed) {
        seed ^= seed >>> 15;
        seed *= 0x2C1B3C6D;
        seed ^= seed >>> 12;
        return ((seed & 0xFFFF) / 65535f - 0.5f) * 0.1f;
    }

    /** Removes particle {@code i} in O(1) by moving the last particle into its slot (order is not kept). */
    public void remove(int i) {
        int last = --activeCount;
        if (i != last) {
            posX[i] = posX[last];
            posY[i] = posY[last];
            posZ[i] = posZ[last];
            prevX[i] = prevX[last];
            prevY[i] = prevY[last];
            prevZ[i] = prevZ[last];
            predX[i] = predX[last];
            predY[i] = predY[last];
            predZ[i] = predZ[last];
            velX[i] = velX[last];
            velY[i] = velY[last];
            velZ[i] = velZ[last];
            lambda[i] = lambda[last];
            material[i] = material[last];
            sleepTicks[i] = sleepTicks[last];
            neighbors[i] = neighbors[last];
        }
    }

    public void clear() {
        activeCount = 0;
    }

    /**
     * Replaces this instance's particles and origin with copies of {@code source}'s, e.g. to move them into larger
     * arrays. The runtime limit of this instance is not changed.
     */
    public void copyFrom(ParticleSoA source) {
        int n = source.activeCount;
        if (n > capacity) {
            throw new IllegalArgumentException("source holds " + n + " particles, capacity is " + capacity);
        }
        System.arraycopy(source.posX, 0, posX, 0, n);
        System.arraycopy(source.posY, 0, posY, 0, n);
        System.arraycopy(source.posZ, 0, posZ, 0, n);
        System.arraycopy(source.prevX, 0, prevX, 0, n);
        System.arraycopy(source.prevY, 0, prevY, 0, n);
        System.arraycopy(source.prevZ, 0, prevZ, 0, n);
        System.arraycopy(source.predX, 0, predX, 0, n);
        System.arraycopy(source.predY, 0, predY, 0, n);
        System.arraycopy(source.predZ, 0, predZ, 0, n);
        System.arraycopy(source.velX, 0, velX, 0, n);
        System.arraycopy(source.velY, 0, velY, 0, n);
        System.arraycopy(source.velZ, 0, velZ, 0, n);
        System.arraycopy(source.lambda, 0, lambda, 0, n);
        System.arraycopy(source.material, 0, material, 0, n);
        System.arraycopy(source.sleepTicks, 0, sleepTicks, 0, n);
        System.arraycopy(source.neighbors, 0, neighbors, 0, n);
        activeCount = n;
        originX = source.originX;
        originY = source.originY;
        originZ = source.originZ;
    }

    /** Sets the origin without moving anything; only valid while no particles exist. */
    public void setOrigin(int x, int y, int z) {
        if (activeCount != 0) {
            throw new IllegalStateException("setOrigin with live particles would move them; use rebase()");
        }
        originX = x;
        originY = y;
        originZ = z;
    }

    /** Moves the origin to a new block and shifts every stored position so world positions stay the same. */
    public void rebase(int newOriginX, int newOriginY, int newOriginZ) {
        float dx = originX - newOriginX;
        float dy = originY - newOriginY;
        float dz = originZ - newOriginZ;
        for (int i = 0; i < activeCount; i++) {
            posX[i] += dx;
            posY[i] += dy;
            posZ[i] += dz;
            prevX[i] += dx;
            prevY[i] += dy;
            prevZ[i] += dz;
            predX[i] += dx;
            predY[i] += dy;
            predZ[i] += dz;
        }
        originX = newOriginX;
        originY = newOriginY;
        originZ = newOriginZ;
    }

    public double worldX(int i) {
        return originX + (double) posX[i];
    }

    public double worldY(int i) {
        return originY + (double) posY[i];
    }

    public double worldZ(int i) {
        return originZ + (double) posZ[i];
    }
}
