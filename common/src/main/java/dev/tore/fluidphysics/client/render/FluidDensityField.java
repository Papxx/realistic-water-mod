// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.client.render;

import dev.tore.fluidphysics.physics.ChunkVoxelCache;
import dev.tore.fluidphysics.physics.MaterialProperties;
import dev.tore.fluidphysics.physics.ParticleSoA;
import java.util.Arrays;

/**
 * The scalar "metaball" density field of one fluid material, rebuilt every frame from the render-interpolated particles
 * (Plan.md §8.1). {@link MarchingCubesMesher} extracts its iso-surface.
 *
 * <p><b>Grid.</b> Global node {@code (gx, gy, gz)} sits at the origin-relative position {@code (gx, gy, gz) * }
 * {@link #CELL_SIZE}. The particle origin is an integer block, so the grid is world-aligned and the surface does not
 * swim when the camera moves.
 *
 * <p><b>Sparse tiles.</b> Only tiles of {@link #TILE_CELLS}^3 cells (2 x 2 x 2 blocks) that particles reach are
 * stored. Tile {@code T} owns the cells {@code 16 T .. 16 T + 15} per axis, which need the nodes {@code 0..16}; the
 * central-difference gradients at those nodes need one more node on each side, so each tile stores the local nodes
 * {@code -1..17} per axis ({@link #TILE_NODES}^3 floats). Neighbouring tiles therefore store some nodes twice, but the
 * mesher never has to look into another tile.
 *
 * <p><b>Deterministic splat.</b> Every node value is the sum of the particle kernels in ascending particle order,
 * computed with the same float arithmetic in every tile that stores the node. Shared nodes are bit-identical in all
 * tiles, which is what lets the mesher compute border vertices independently per tile and still produce a crack-free,
 * watertight mesh.
 *
 * <p><b>Closed by construction.</b> A particle allocates every tile containing a cell that touches one of its non-zero
 * nodes. So every node with a non-zero value has all eight surrounding cells inside allocated tiles, and the nodes on
 * the border between an allocated and a missing tile are zero (outside): the surface never runs into a missing tile.
 * When {@link #MAX_TILES} would be exceeded, the particle is left out entirely ({@link #isSkipped}), so the field stays
 * consistent and closed without it.
 *
 * <p><b>Coarse fields.</b> Each tile also keeps three fields on a coarse grid with {@link #COARSE_SIZE} (half a block)
 * spacing, local coarse nodes {@code -1..5} per axis ({@link #COARSE_NODES}^3 floats each): a trilinear particle count
 * (Beer-Lambert thickness, Plan.md §8.4.5), and for the ripple rings (Plan.md §8.2) an impact field from fast particles
 * plus the distance to the nearest impact source. The apron of one coarse node covers every sample the mesher takes:
 * thickness half a coarse cell or so below the surface, and ripple gradients half a coarse cell to either side of a
 * vertex. An apron of two coarse nodes (9^3 floats) would only add splat work and memory.
 *
 * <p><b>Impact sources.</b> Fast particles are pooled per block into one source at their amount-weighted centroid, and
 * each source is splatted once. A source's kernel covers about a dozen tiles' overlapping coarse nodes, so splatting
 * every fast particle made a stirred pool of 32k particles cost about 40 ms per frame; pooled, it costs one splat per
 * block. An isolated fast particle is its own source, so it gives exactly the per-particle kernel. The ripple phase
 * uses the distance to the nearest source instead of inverting the summed kernel: for one source of amount 1 both are
 * the same, but a strong stream sums to far more than 1, where the inverted kernel saturates and the rings would
 * collapse into the outermost fringe.
 *
 * <p>Allocation-free once the buffers have grown to the largest frame seen; memory only grows when needed. Not
 * thread-safe: render thread only. {@link #build} reads the voxel cache, so call it while nothing modifies the cache.
 */
public final class FluidDensityField {
    /** Grid spacing {@code ds}: 8 cells per block (Plan.md §8.1). */
    public static final float CELL_SIZE = 0.125f;
    public static final int CELLS_PER_BLOCK = 8;
    /** A tile is 16^3 cells, 2 x 2 x 2 blocks. */
    public static final int TILE_CELLS = 16;
    /** Most tiles a field holds; particles whose tiles do not fit are skipped (drawn as cubes instead). */
    public static final int MAX_TILES = 1024;
    /** Kernel radius {@code R}: {@code w = max(0, 1 - q)^3} with {@code q = (dx/rx)^2 + (dy/ry)^2 + (dz/rz)^2}. */
    public static final float KERNEL_RADIUS = 0.32f;
    /** Horizontal radius factor of a particle resting on solid ground: puddles spread into thin edges. */
    public static final float WET_STRETCH = 1.35f;
    /** Vertical radius factor of a particle resting on solid ground: puddles get flat. */
    public static final float WET_SQUASH = 0.65f;

    /** {@code 1 / CELL_SIZE}. */
    static final float INV_CELL_SIZE = CELLS_PER_BLOCK;
    /** Extra nodes stored on each side of a tile, for central-difference gradients at its border nodes. */
    static final int NODE_APRON = 1;
    /** Nodes per axis stored in a tile: local nodes {@code -1..17}. */
    static final int TILE_NODES = TILE_CELLS + 1 + 2 * NODE_APRON;
    static final int NODE_STRIDE_Y = TILE_NODES;
    static final int NODE_STRIDE_Z = TILE_NODES * TILE_NODES;
    /** Floats per tile of the fine field (6859). */
    static final int TILE_VOLUME = TILE_NODES * NODE_STRIDE_Z;

    /** Fine cells per coarse cell. */
    static final int COARSE_STEP = 4;
    /** Coarse grid spacing in blocks. */
    static final float COARSE_SIZE = COARSE_STEP * CELL_SIZE;
    static final float INV_COARSE_SIZE = 1f / COARSE_SIZE;
    /** Coarse cells per tile and axis. */
    static final int COARSE_CELLS = TILE_CELLS / COARSE_STEP;
    /** Extra coarse nodes stored on each side of a tile. */
    static final int COARSE_APRON = 1;
    /** Coarse nodes per axis stored in a tile: local coarse nodes {@code -1..5}. */
    static final int COARSE_NODES = COARSE_CELLS + 1 + 2 * COARSE_APRON;
    static final int COARSE_STRIDE_Y = COARSE_NODES;
    static final int COARSE_STRIDE_Z = COARSE_NODES * COARSE_NODES;
    /** Floats per tile of each coarse field (343). */
    static final int COARSE_VOLUME = COARSE_NODES * COARSE_STRIDE_Z;
    /**
     * Coarse count of a fully packed block: 64 particles per block deposit 64 * 0.5^3 = 8 on each coarse node. A
     * sampled count of this much reads as full thickness.
     */
    static final float FULL_COUNT = ParticleSoA.PARTICLES_PER_BLOCK * COARSE_SIZE * COARSE_SIZE * COARSE_SIZE;

    /**
     * Depth below a particle's centre (blocks) where the floor is probed for wetting: a particle is about half the
     * 0.25-block spacing high, so a resting one has solid ground within this distance.
     */
    static final float WET_PROBE_DEPTH = 0.15f;
    /** Sub-voxels per block and axis in {@link ChunkVoxelCache} masks. */
    private static final int SUB_VOXELS = 4;
    /** Particles faster than this (blocks per second) stir the impact field ... */
    static final float IMPACT_MIN_SPEED = 1f;
    /** ... and fully from this speed on. */
    static final float IMPACT_FULL_SPEED = 6f;
    /**
     * Radius (blocks) of an impact source's kernel {@code amount * (1 - (r/R)^2)^3}, and the distance at which the
     * source distance field reads 1 (far).
     */
    static final float IMPACT_RADIUS = 1f;
    private static final float INV_IMPACT_RADIUS_SQ = 1f / (IMPACT_RADIUS * IMPACT_RADIUS);
    /** Source distance ({@code r / IMPACT_RADIUS}) of coarse nodes no source reaches. */
    static final float FAR_SOURCE = 1f;
    private static final int MIN_BIN_TABLE = 64;

    /** Per-axis node count bound of one particle's support; the largest radius (0.432 blocks) needs at most 7. */
    private static final int MAX_SUPPORT_NODES = 16;
    /** Per-axis coarse node count bound of the impact kernel (radius 2 coarse cells: at most 5). */
    private static final int MAX_IMPACT_NODES = 8;

    /** Open-addressing table from packed tile key to slot; a power of two, 4x {@link #MAX_TILES} for short probes. */
    private static final int HASH_SIZE = 4096;
    private static final int HASH_MASK = HASH_SIZE - 1;
    private static final int TILE_COORD_BITS = 21;
    private static final long TILE_COORD_MASK = (1L << TILE_COORD_BITS) - 1;
    /** Tiles the buffers start with on first use. */
    private static final int INITIAL_TILES = 16;

    private static final byte STATE_NONE = 0;
    private static final byte STATE_DRY = 1;
    private static final byte STATE_WET = 2;
    private static final byte STATE_SKIPPED = 3;

    // Tile table. An entry is live when its stamp equals the build's stamp, so no clearing is needed per build.
    private final long[] hashKeys = new long[HASH_SIZE];
    private final int[] hashSlots = new int[HASH_SIZE];
    private final int[] hashStamps = new int[HASH_SIZE];
    private int stamp;
    private final int[] tileXs = new int[MAX_TILES];
    private final int[] tileYs = new int[MAX_TILES];
    private final int[] tileZs = new int[MAX_TILES];
    private int tileCount;

    // Field data of all tiles, slot-major; grown by doubling, never shrunk.
    private float[] values = new float[0];
    private float[] counts = new float[0];
    private float[] impacts = new float[0];
    private float[] sourceDistances = new float[0];

    // Impact sources of the last build: one bin per block with fast particles, in order of first use. The table maps a
    // packed block key to a bin; it is sized for one bin per particle, so it never fills up.
    private long[] binKeys = new long[0];
    private int[] binSlots = new int[0];
    private int[] binStamps = new int[0];
    private int binStamp;
    private int binMask;
    private float[] binAmount = new float[0];
    private float[] binX = new float[0];
    private float[] binY = new float[0];
    private float[] binZ = new float[0];
    private int binCount;

    // Per particle of the last build: interpolated origin-relative position and state; members in ascending order.
    private float[] px = new float[0];
    private float[] py = new float[0];
    private float[] pz = new float[0];
    private byte[] state = new byte[0];
    private int[] members = new int[0];
    private int memberCount;
    private int builtParticles;
    private int skippedCount;
    private boolean overflowed;

    private int originX;
    private int originY;
    private int originZ;
    private byte material;

    // Support of the particle being processed: node range per axis and the per-axis kernel terms (dx/rx)^2 etc.
    private int nx0;
    private int nx1;
    private int ny0;
    private int ny1;
    private int nz0;
    private int nz1;
    private final float[] termX = new float[MAX_SUPPORT_NODES];
    private final float[] termY = new float[MAX_SUPPORT_NODES];
    private final float[] termZ = new float[MAX_SUPPORT_NODES];
    private final float[] impactX = new float[MAX_IMPACT_NODES];
    private final float[] impactY = new float[MAX_IMPACT_NODES];
    private final float[] impactZ = new float[MAX_IMPACT_NODES];

    // One-entry memo of the last voxel mask read while wetting (the solver stores particles in grid order).
    private boolean maskValid;
    private int maskX;
    private int maskY;
    private int maskZ;
    private long mask;

    /**
     * Builds the field of one material from the render-interpolated particles. {@code voxels} may be null (no floor
     * wetting). Returns the number of tiles (0 when the material has no particles). Allocation-free once grown.
     *
     * @param partialTick fraction of the current tick that has passed, 0..1
     */
    public int build(ParticleSoA p, float partialTick, byte material, ChunkVoxelCache voxels) {
        int n = p.activeCount;
        ensureParticleCapacity(n);
        builtParticles = n;
        originX = p.originX;
        originY = p.originY;
        originZ = p.originZ;
        this.material = material;
        nextStamp();
        tileCount = 0;
        memberCount = 0;
        skippedCount = 0;
        overflowed = false;
        maskValid = false;
        int collisionClass = voxels == null ? 0 : MaterialProperties.collisionClass(material);
        float t = partialTick;

        // Pass 1: interpolate, decide wetting and allocate the tiles each particle needs (or skip it).
        for (int i = 0; i < n; i++) {
            if (p.material[i] != material) {
                state[i] = STATE_NONE;
                continue;
            }
            float x = p.prevX[i] + (p.posX[i] - p.prevX[i]) * t;
            float y = p.prevY[i] + (p.posY[i] - p.prevY[i]) * t;
            float z = p.prevZ[i] + (p.posZ[i] - p.prevZ[i]) * t;
            if (!(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z))) {
                state[i] = STATE_NONE; // a broken particle has no place in the field, and none as a cube either
                continue;
            }
            boolean wet = voxels != null && restsOnSolid(voxels, collisionClass, x, y, z);
            supportRange(x, y, z, wet);
            // Cells nmin - 1 .. nmax touch a supported node.
            if (!reserveTiles(Math.floorDiv(nx0 - 1, TILE_CELLS), Math.floorDiv(nx1, TILE_CELLS),
                    Math.floorDiv(ny0 - 1, TILE_CELLS), Math.floorDiv(ny1, TILE_CELLS),
                    Math.floorDiv(nz0 - 1, TILE_CELLS), Math.floorDiv(nz1, TILE_CELLS))) {
                state[i] = STATE_SKIPPED;
                skippedCount++;
                overflowed = true;
                continue;
            }
            px[i] = x;
            py[i] = y;
            pz[i] = z;
            state[i] = wet ? STATE_WET : STATE_DRY;
            members[memberCount++] = i;
        }
        if (tileCount == 0) {
            return 0;
        }
        ensureTileCapacity(tileCount);
        Arrays.fill(values, 0, tileCount * TILE_VOLUME, 0f);
        Arrays.fill(counts, 0, tileCount * COARSE_VOLUME, 0f);
        Arrays.fill(impacts, 0, tileCount * COARSE_VOLUME, 0f);
        Arrays.fill(sourceDistances, 0, tileCount * COARSE_VOLUME, FAR_SOURCE);
        ensureBinCapacity(memberCount);
        nextBinStamp();
        binCount = 0;

        // Pass 2: splat in ascending particle order, so shared nodes come out bit-identical in every tile.
        for (int m = 0; m < memberCount; m++) {
            int i = members[m];
            float x = px[i];
            float y = py[i];
            float z = pz[i];
            splatKernel(x, y, z, state[i] == STATE_WET);
            splatCount(x, y, z);
            float vx = p.velX[i];
            float vy = p.velY[i];
            float vz = p.velZ[i];
            float speed = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (speed > IMPACT_MIN_SPEED) {
                addToBin(x, y, z, smoothstep(IMPACT_MIN_SPEED, IMPACT_FULL_SPEED, speed));
            }
        }
        // Impact sources, in order of first use (deterministic, like the particles).
        for (int b = 0; b < binCount; b++) {
            float amount = binAmount[b];
            float inv = 1f / amount;
            splatImpact(binX[b] * inv, binY[b] * inv, binZ[b] * inv, amount);
        }
        return tileCount;
    }

    /** Adds a fast particle's impact amount to the source of its block (amount-weighted centroid). */
    private void addToBin(float x, float y, float z, float amount) {
        long key = tileKey((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        int h = hash(key) & binMask;
        int bin = -1;
        while (binStamps[h] == binStamp) {
            if (binKeys[h] == key) {
                bin = binSlots[h];
                break;
            }
            h = (h + 1) & binMask;
        }
        if (bin < 0) {
            bin = binCount++;
            binStamps[h] = binStamp;
            binKeys[h] = key;
            binSlots[h] = bin;
            binAmount[bin] = 0f;
            binX[bin] = 0f;
            binY[bin] = 0f;
            binZ[bin] = 0f;
        }
        binAmount[bin] += amount;
        binX[bin] += amount * x;
        binY[bin] += amount * y;
        binZ[bin] += amount * z;
    }

    public int tileCount() {
        return tileCount;
    }

    /** Whether any particle of the last build could not be placed because MAX_TILES was reached. */
    public boolean overflowed() {
        return overflowed;
    }

    /**
     * Valid until the next build: particle i has the built material but its tiles did not fit (draw it as a cube).
     * False for particles of other materials, indices outside the last build, and particles with a non-finite
     * position (those are left out of the field and should not be drawn at all).
     */
    public boolean isSkipped(int i) {
        return i >= 0 && i < builtParticles && state[i] == STATE_SKIPPED;
    }

    /** Number of particles {@link #isSkipped} is true for. */
    int skippedCount() {
        return skippedCount;
    }

    /** The material of the last build. */
    byte material() {
        return material;
    }

    /** Particle origin of the last build: global node {@code g} sits at world {@code originX + g * CELL_SIZE}. */
    int originX() {
        return originX;
    }

    int originY() {
        return originY;
    }

    int originZ() {
        return originZ;
    }

    /** Tile coordinates of a slot (0..tileCount-1): the tile owns global cells {@code 16 tx .. 16 tx + 15}. */
    int tileX(int slot) {
        return tileXs[slot];
    }

    int tileY(int slot) {
        return tileYs[slot];
    }

    int tileZ(int slot) {
        return tileZs[slot];
    }

    /** Slot of tile ({@code tx}, {@code ty}, {@code tz}) in the last build, or -1 if it is not allocated. */
    int findTile(int tx, int ty, int tz) {
        return findSlot(tx, ty, tz);
    }

    /**
     * The fine field of all tiles. Local node ({@code lx}, {@code ly}, {@code lz}), each in -1..17, of a slot is at
     * {@code tileBase(slot) + nodeOffset(lx, ly, lz)}.
     */
    float[] values() {
        return values;
    }

    static int tileBase(int slot) {
        return slot * TILE_VOLUME;
    }

    static int nodeOffset(int lx, int ly, int lz) {
        return (lx + NODE_APRON) + (ly + NODE_APRON) * NODE_STRIDE_Y + (lz + NODE_APRON) * NODE_STRIDE_Z;
    }

    /** Value of local node ({@code lx}, {@code ly}, {@code lz}), each in -1..17, of a slot. */
    float nodeValue(int slot, int lx, int ly, int lz) {
        return values[tileBase(slot) + nodeOffset(lx, ly, lz)];
    }

    /**
     * Fluid thickness 0..1 near a point: the trilinear particle count relative to {@link #FULL_COUNT}. The point is in
     * global fine-node units (origin-relative blocks times {@link #CELLS_PER_BLOCK}) and is clamped into the slot's
     * coarse nodes.
     */
    float thickness(int slot, float gx, float gy, float gz) {
        return clamp01(sampleCoarse(counts, slot, gx, gy, gz) * (1f / FULL_COUNT));
    }

    /** The impact field (0 = calm, about 1 and more under a stream) at a point in global fine-node units. */
    float impact(int slot, float gx, float gy, float gz) {
        return sampleCoarse(impacts, slot, gx, gy, gz);
    }

    /**
     * Distance to the nearest impact source relative to {@link #IMPACT_RADIUS} (0 at a source, {@link #FAR_SOURCE}
     * beyond its reach), interpolated between coarse nodes, at a point in global fine-node units.
     */
    float sourceDistance(int slot, float gx, float gy, float gz) {
        return sampleCoarse(sourceDistances, slot, gx, gy, gz);
    }

    /**
     * Trilinear sample of a coarse field. Works in global coarse coordinates, so a point on a tile border reads the
     * same weights in both tiles.
     */
    private float sampleCoarse(float[] field, int slot, float gx, float gy, float gz) {
        float cx = gx * (1f / COARSE_STEP);
        float cy = gy * (1f / COARSE_STEP);
        float cz = gz * (1f / COARSE_STEP);
        int ox = tileXs[slot] * COARSE_CELLS - COARSE_APRON;
        int oy = tileYs[slot] * COARSE_CELLS - COARSE_APRON;
        int oz = tileZs[slot] * COARSE_CELLS - COARSE_APRON;
        int x0 = clamp((int) Math.floor(cx), ox, ox + COARSE_NODES - 2);
        int y0 = clamp((int) Math.floor(cy), oy, oy + COARSE_NODES - 2);
        int z0 = clamp((int) Math.floor(cz), oz, oz + COARSE_NODES - 2);
        float fx = clamp01(cx - x0);
        float fy = clamp01(cy - y0);
        float fz = clamp01(cz - z0);
        int i = slot * COARSE_VOLUME + (x0 - ox) + (y0 - oy) * COARSE_STRIDE_Y + (z0 - oz) * COARSE_STRIDE_Z;
        float c00 = lerp(field[i], field[i + 1], fx);
        float c10 = lerp(field[i + COARSE_STRIDE_Y], field[i + COARSE_STRIDE_Y + 1], fx);
        float c01 = lerp(field[i + COARSE_STRIDE_Z], field[i + COARSE_STRIDE_Z + 1], fx);
        float c11 = lerp(field[i + COARSE_STRIDE_Y + COARSE_STRIDE_Z], field[i + COARSE_STRIDE_Y + COARSE_STRIDE_Z + 1],
                fx);
        return lerp(lerp(c00, c10, fy), lerp(c01, c11, fy), fz);
    }

    /** Whether the sub-voxel {@link #WET_PROBE_DEPTH} below a particle is solid for its material. */
    private boolean restsOnSolid(ChunkVoxelCache voxels, int collisionClass, float x, float y, float z) {
        double wx = originX + (double) x;
        double wy = originY + (double) y - WET_PROBE_DEPTH;
        double wz = originZ + (double) z;
        int bx = (int) Math.floor(wx);
        int by = (int) Math.floor(wy);
        int bz = (int) Math.floor(wz);
        if (!maskValid || bx != maskX || by != maskY || bz != maskZ) {
            mask = voxels.getMask(bx, by, bz, collisionClass);
            maskX = bx;
            maskY = by;
            maskZ = bz;
            maskValid = true;
        }
        if (mask == ChunkVoxelCache.EMPTY) {
            return false;
        }
        int sx = Math.min(SUB_VOXELS - 1, (int) ((wx - bx) * SUB_VOXELS));
        int sy = Math.min(SUB_VOXELS - 1, (int) ((wy - by) * SUB_VOXELS));
        int sz = Math.min(SUB_VOXELS - 1, (int) ((wz - bz) * SUB_VOXELS));
        return ChunkVoxelCache.isSolid(mask, sx, sy, sz);
    }

    /**
     * Sets the node range of a particle's kernel support per axis ({@link #nx0}..{@link #nx1} etc.): the nodes closer
     * than the radius along that axis. Every node outside this range has a kernel term of at least 1 and so weight 0;
     * the range alone decides which nodes and tiles a particle touches, so tiles and splat always agree.
     */
    private void supportRange(float x, float y, float z, boolean wet) {
        float rh = wet ? KERNEL_RADIUS * WET_STRETCH : KERNEL_RADIUS;
        float rv = wet ? KERNEL_RADIUS * WET_SQUASH : KERNEL_RADIUS;
        nx0 = (int) Math.floor((x - rh) * INV_CELL_SIZE) + 1;
        nx1 = (int) Math.ceil((x + rh) * INV_CELL_SIZE) - 1;
        ny0 = (int) Math.floor((y - rv) * INV_CELL_SIZE) + 1;
        ny1 = (int) Math.ceil((y + rv) * INV_CELL_SIZE) - 1;
        nz0 = (int) Math.floor((z - rh) * INV_CELL_SIZE) + 1;
        nz1 = (int) Math.ceil((z + rh) * INV_CELL_SIZE) - 1;
    }

    /** {@link #supportRange} plus the per-axis kernel terms {@code (d / r)^2} of the supported nodes. */
    private void support(float x, float y, float z, boolean wet) {
        supportRange(x, y, z, wet);
        float rh = wet ? KERNEL_RADIUS * WET_STRETCH : KERNEL_RADIUS;
        float rv = wet ? KERNEL_RADIUS * WET_SQUASH : KERNEL_RADIUS;
        float invH = 1f / (rh * rh);
        float invV = 1f / (rv * rv);
        axisTerms(termX, nx0, nx1, x, invH);
        axisTerms(termY, ny0, ny1, y, invV);
        axisTerms(termZ, nz0, nz1, z, invH);
    }

    private static void axisTerms(float[] terms, int n0, int n1, float center, float invRadiusSq) {
        for (int g = n0; g <= n1; g++) {
            float d = g * CELL_SIZE - center;
            terms[g - n0] = d * d * invRadiusSq;
        }
    }

    /**
     * Adds a particle's kernel to every allocated tile whose stored nodes (local -1..17) meet its support. The terms
     * are computed once per particle and combined the same way in every tile, so shared nodes get identical sums.
     */
    private void splatKernel(float x, float y, float z, boolean wet) {
        support(x, y, z, wet);
        // Tile T stores the global nodes 16 T - 1 .. 16 T + 17.
        int tx0 = Math.floorDiv(nx0 - 2, TILE_CELLS);
        int tx1 = Math.floorDiv(nx1 + 1, TILE_CELLS);
        int ty0 = Math.floorDiv(ny0 - 2, TILE_CELLS);
        int ty1 = Math.floorDiv(ny1 + 1, TILE_CELLS);
        int tz0 = Math.floorDiv(nz0 - 2, TILE_CELLS);
        int tz1 = Math.floorDiv(nz1 + 1, TILE_CELLS);
        float[] v = values;
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int ty = ty0; ty <= ty1; ty++) {
                for (int tx = tx0; tx <= tx1; tx++) {
                    int slot = findSlot(tx, ty, tz);
                    if (slot < 0) {
                        continue;
                    }
                    int ox = tx * TILE_CELLS - NODE_APRON;
                    int oy = ty * TILE_CELLS - NODE_APRON;
                    int oz = tz * TILE_CELLS - NODE_APRON;
                    int xLo = Math.max(nx0, ox);
                    int xHi = Math.min(nx1, ox + TILE_NODES - 1);
                    int yLo = Math.max(ny0, oy);
                    int yHi = Math.min(ny1, oy + TILE_NODES - 1);
                    int zLo = Math.max(nz0, oz);
                    int zHi = Math.min(nz1, oz + TILE_NODES - 1);
                    int base = tileBase(slot) - ox;
                    for (int gz = zLo; gz <= zHi; gz++) {
                        float qz = termZ[gz - nz0];
                        int rowZ = base + (gz - oz) * NODE_STRIDE_Z;
                        for (int gy = yLo; gy <= yHi; gy++) {
                            float qyz = termY[gy - ny0] + qz;
                            if (qyz >= 1f) {
                                continue;
                            }
                            int row = rowZ + (gy - oy) * NODE_STRIDE_Y;
                            for (int gx = xLo; gx <= xHi; gx++) {
                                float s = 1f - (qyz + termX[gx - nx0]);
                                // max(s, 0) without a branch: whether a node of a displaced particle lies inside
                                // its ellipsoid is unpredictable, and mispredictions cost about 40 % of the splat.
                                // Exact in float, so shared nodes stay bit-identical; outside nodes add +0.
                                s = (s + Math.abs(s)) * 0.5f;
                                v[row + gx] += s * s * s;
                            }
                        }
                    }
                }
            }
        }
    }

    /** Adds a particle's trilinear weights to the 8 coarse nodes around it, in every allocated tile storing them. */
    private void splatCount(float x, float y, float z) {
        float cx = x * INV_COARSE_SIZE;
        float cy = y * INV_COARSE_SIZE;
        float cz = z * INV_COARSE_SIZE;
        int x0 = (int) Math.floor(cx);
        int y0 = (int) Math.floor(cy);
        int z0 = (int) Math.floor(cz);
        float fx = cx - x0;
        float fy = cy - y0;
        float fz = cz - z0;
        // Tile T stores the global coarse nodes 4 T - 1 .. 4 T + 5; the particle touches x0 .. x0 + 1.
        int tx0 = Math.floorDiv(x0 - 2, COARSE_CELLS);
        int tx1 = Math.floorDiv(x0 + 2, COARSE_CELLS);
        int ty0 = Math.floorDiv(y0 - 2, COARSE_CELLS);
        int ty1 = Math.floorDiv(y0 + 2, COARSE_CELLS);
        int tz0 = Math.floorDiv(z0 - 2, COARSE_CELLS);
        int tz1 = Math.floorDiv(z0 + 2, COARSE_CELLS);
        float[] c = counts;
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int ty = ty0; ty <= ty1; ty++) {
                for (int tx = tx0; tx <= tx1; tx++) {
                    int slot = findSlot(tx, ty, tz);
                    if (slot < 0) {
                        continue;
                    }
                    int ox = tx * COARSE_CELLS - COARSE_APRON;
                    int oy = ty * COARSE_CELLS - COARSE_APRON;
                    int oz = tz * COARSE_CELLS - COARSE_APRON;
                    int base = slot * COARSE_VOLUME;
                    for (int dz = 0; dz < 2; dz++) {
                        int lz = z0 + dz - oz;
                        if (lz < 0 || lz >= COARSE_NODES) {
                            continue;
                        }
                        float wz = dz == 0 ? 1f - fz : fz;
                        for (int dy = 0; dy < 2; dy++) {
                            int ly = y0 + dy - oy;
                            if (ly < 0 || ly >= COARSE_NODES) {
                                continue;
                            }
                            float wyz = (dy == 0 ? 1f - fy : fy) * wz;
                            int row = base + ly * COARSE_STRIDE_Y + lz * COARSE_STRIDE_Z;
                            for (int dx = 0; dx < 2; dx++) {
                                int lx = x0 + dx - ox;
                                if (lx < 0 || lx >= COARSE_NODES) {
                                    continue;
                                }
                                c[row + lx] += wyz * (dx == 0 ? 1f - fx : fx);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Adds {@code amount * (1 - (r / IMPACT_RADIUS)^2)^3} to the coarse impact nodes within {@link #IMPACT_RADIUS} of
     * an impact source and lowers their source distance to {@code r / IMPACT_RADIUS}, in every allocated tile storing
     * them.
     */
    private void splatImpact(float x, float y, float z, float amount) {
        float cx = x * INV_COARSE_SIZE;
        float cy = y * INV_COARSE_SIZE;
        float cz = z * INV_COARSE_SIZE;
        float rc = IMPACT_RADIUS * INV_COARSE_SIZE;
        int x0 = (int) Math.floor(cx - rc) + 1;
        int x1 = (int) Math.ceil(cx + rc) - 1;
        int y0 = (int) Math.floor(cy - rc) + 1;
        int y1 = (int) Math.ceil(cy + rc) - 1;
        int z0 = (int) Math.floor(cz - rc) + 1;
        int z1 = (int) Math.ceil(cz + rc) - 1;
        impactTerms(impactX, x0, x1, x);
        impactTerms(impactY, y0, y1, y);
        impactTerms(impactZ, z0, z1, z);
        int tx0 = Math.floorDiv(x0 - 2, COARSE_CELLS);
        int tx1 = Math.floorDiv(x1 + 1, COARSE_CELLS);
        int ty0 = Math.floorDiv(y0 - 2, COARSE_CELLS);
        int ty1 = Math.floorDiv(y1 + 1, COARSE_CELLS);
        int tz0 = Math.floorDiv(z0 - 2, COARSE_CELLS);
        int tz1 = Math.floorDiv(z1 + 1, COARSE_CELLS);
        float[] f = impacts;
        float[] d = sourceDistances;
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int ty = ty0; ty <= ty1; ty++) {
                for (int tx = tx0; tx <= tx1; tx++) {
                    int slot = findSlot(tx, ty, tz);
                    if (slot < 0) {
                        continue;
                    }
                    int ox = tx * COARSE_CELLS - COARSE_APRON;
                    int oy = ty * COARSE_CELLS - COARSE_APRON;
                    int oz = tz * COARSE_CELLS - COARSE_APRON;
                    int xLo = Math.max(x0, ox);
                    int xHi = Math.min(x1, ox + COARSE_NODES - 1);
                    int yLo = Math.max(y0, oy);
                    int yHi = Math.min(y1, oy + COARSE_NODES - 1);
                    int zLo = Math.max(z0, oz);
                    int zHi = Math.min(z1, oz + COARSE_NODES - 1);
                    int base = slot * COARSE_VOLUME - ox;
                    for (int gz = zLo; gz <= zHi; gz++) {
                        float qz = impactZ[gz - z0];
                        int rowZ = base + (gz - oz) * COARSE_STRIDE_Z;
                        for (int gy = yLo; gy <= yHi; gy++) {
                            float qyz = impactY[gy - y0] + qz;
                            if (qyz >= 1f) {
                                continue;
                            }
                            int row = rowZ + (gy - oy) * COARSE_STRIDE_Y;
                            for (int gx = xLo; gx <= xHi; gx++) {
                                float q = qyz + impactX[gx - x0];
                                if (q < 1f) {
                                    float s = 1f - q;
                                    f[row + gx] += amount * (s * s * s);
                                    float r = (float) Math.sqrt(q);
                                    if (r < d[row + gx]) {
                                        d[row + gx] = r;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void impactTerms(float[] terms, int c0, int c1, float center) {
        for (int c = c0; c <= c1; c++) {
            float d = c * COARSE_SIZE - center;
            terms[c - c0] = d * d * INV_IMPACT_RADIUS_SQ;
        }
    }

    /**
     * Makes sure the tiles {@code tx0..tx1} x {@code ty0..ty1} x {@code tz0..tz1} are allocated. All or nothing: if
     * the missing ones do not fit under {@link #MAX_TILES}, nothing is allocated and false is returned.
     */
    private boolean reserveTiles(int tx0, int tx1, int ty0, int ty1, int tz0, int tz1) {
        int missing = 0;
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int ty = ty0; ty <= ty1; ty++) {
                for (int tx = tx0; tx <= tx1; tx++) {
                    if (findSlot(tx, ty, tz) < 0) {
                        missing++;
                    }
                }
            }
        }
        if (missing == 0) {
            return true;
        }
        if (tileCount + missing > MAX_TILES) {
            return false;
        }
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int ty = ty0; ty <= ty1; ty++) {
                for (int tx = tx0; tx <= tx1; tx++) {
                    if (findSlot(tx, ty, tz) < 0) {
                        insertSlot(tx, ty, tz);
                    }
                }
            }
        }
        return true;
    }

    private int findSlot(int tx, int ty, int tz) {
        long key = tileKey(tx, ty, tz);
        int h = hash(key) & HASH_MASK;
        while (hashStamps[h] == stamp) {
            if (hashKeys[h] == key) {
                return hashSlots[h];
            }
            h = (h + 1) & HASH_MASK;
        }
        return -1;
    }

    /** Allocates the next slot for a tile known to be missing. */
    private void insertSlot(int tx, int ty, int tz) {
        long key = tileKey(tx, ty, tz);
        int h = hash(key) & HASH_MASK;
        while (hashStamps[h] == stamp) {
            h = (h + 1) & HASH_MASK;
        }
        int slot = tileCount++;
        hashStamps[h] = stamp;
        hashKeys[h] = key;
        hashSlots[h] = slot;
        tileXs[slot] = tx;
        tileYs[slot] = ty;
        tileZs[slot] = tz;
    }

    /** Starts a new generation of the tile table; every entry of the previous build becomes empty. */
    private void nextStamp() {
        if (stamp == Integer.MAX_VALUE) {
            Arrays.fill(hashStamps, 0);
            stamp = 0;
        }
        stamp++;
    }

    private static long tileKey(int tx, int ty, int tz) {
        return ((tx & TILE_COORD_MASK) << (2 * TILE_COORD_BITS)) | ((ty & TILE_COORD_MASK) << TILE_COORD_BITS)
                | (tz & TILE_COORD_MASK);
    }

    /** 64-bit finaliser (MurmurHash3 fmix64), spreads neighbouring tile keys over the table. */
    private static int hash(long key) {
        key ^= key >>> 33;
        key *= 0xFF51AFD7ED558CCDL;
        key ^= key >>> 33;
        key *= 0xC4CEB9FE1A85EC53L;
        key ^= key >>> 33;
        return (int) key;
    }

    /** Grows the tile buffers to hold {@code tiles} tiles, doubling, at most {@link #MAX_TILES}. No copy: rebuilt. */
    private void ensureTileCapacity(int tiles) {
        if (values.length >= tiles * TILE_VOLUME) {
            return;
        }
        int size = Math.max(Math.max(tiles, INITIAL_TILES), values.length / TILE_VOLUME * 2);
        size = Math.min(size, MAX_TILES);
        values = new float[size * TILE_VOLUME];
        counts = new float[size * COARSE_VOLUME];
        impacts = new float[size * COARSE_VOLUME];
        sourceDistances = new float[size * COARSE_VOLUME];
    }

    /**
     * Grows the impact bins to hold one bin per particle, with a table at least twice as large (only when the number
     * of particles of the material grew).
     */
    private void ensureBinCapacity(int particles) {
        if (binAmount.length >= particles && binKeys.length >= MIN_BIN_TABLE) {
            return;
        }
        int bins = Math.max(particles, binAmount.length * 2);
        binAmount = new float[bins];
        binX = new float[bins];
        binY = new float[bins];
        binZ = new float[bins];
        int table = Integer.highestOneBit(Math.max(MIN_BIN_TABLE, bins * 2 - 1)) << 1;
        binKeys = new long[table];
        binSlots = new int[table];
        binStamps = new int[table];
        binMask = table - 1;
        binStamp = 0;
    }

    /** Starts a new generation of the bin table; every bin of the previous build becomes empty. */
    private void nextBinStamp() {
        if (binStamp == Integer.MAX_VALUE) {
            Arrays.fill(binStamps, 0);
            binStamp = 0;
        }
        binStamp++;
    }

    /** Grows the per-particle arrays to hold {@code n} particles (only when the simulation grew). */
    private void ensureParticleCapacity(int n) {
        if (state.length >= n) {
            return;
        }
        int size = Math.min(Math.max(n, state.length * 2), ParticleSoA.MAX_PARTICLES);
        size = Math.max(size, n);
        px = new float[size];
        py = new float[size];
        pz = new float[size];
        state = new byte[size];
        members = new int[size];
    }

    private static float smoothstep(float edge0, float edge1, float v) {
        float t = clamp01((v - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
