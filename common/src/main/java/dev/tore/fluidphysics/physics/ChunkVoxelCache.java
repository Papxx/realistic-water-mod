// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Solid world geometry around the particles, one {@code long} per block: each of the 64 bits marks one
 * 0.25-block sub-voxel of the block's collision shape as solid (see {@link #subVoxelBit}).
 *
 * <p>The solver only calls {@link #getMask}, which never touches the level. The level is read on the main thread,
 * a whole chunk section (16 x 16 x 16 blocks) at a time, by {@link #ensureCached} before the solver runs and by
 * {@link #onBlockChanged} when a block changes. Blocks that are not stored read as air: that covers real air,
 * fluids, and sections that are not cached (e.g. in unloaded chunks), so callers must not simulate particles in
 * uncached sections.
 *
 * <p>Threading: {@link #getMask} may be called from several solver threads at once, but only while nothing changes
 * the cache; all other methods belong to the main thread and must not run during a solver step.
 */
public final class ChunkVoxelCache {
    public static final long EMPTY = 0L;
    public static final long FULL = -1L;

    /** Overlap (in sub-voxel units) a collision box needs before a sub-voxel counts as solid. */
    private static final double OVERLAP_EPSILON = 1.0e-3;

    /** Packed block position ({@link BlockPos#asLong(int, int, int)}) to sub-voxel mask; absent means air. */
    private final Long2LongOpenHashMap masks = new Long2LongOpenHashMap();
    /** Sections ({@link SectionPos#asLong(int, int, int)}) whose blocks are all in {@link #masks}. */
    private final LongOpenHashSet cachedSections = new LongOpenHashSet();
    /** Masks of block states whose collision shape does not depend on the position. */
    private final Reference2LongOpenHashMap<BlockState> stateMasks = new Reference2LongOpenHashMap<>();
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();
    /** Bumped whenever any stored mask may have changed, so solvers know to drop masks they remembered. */
    private int version;

    public ChunkVoxelCache() {
        masks.defaultReturnValue(EMPTY);
    }

    /** Bit index of sub-voxel ({@code sx}, {@code sy}, {@code sz}), each in 0..3. */
    public static int subVoxelBit(int sx, int sy, int sz) {
        return (sy << 4) | (sz << 2) | sx;
    }

    public static boolean isSolid(long blockMask, int sx, int sy, int sz) {
        return (blockMask & (1L << subVoxelBit(sx, sy, sz))) != 0L;
    }

    /** Sub-voxel mask of the block at world block coordinates; {@link #EMPTY} if air or unknown. */
    public long getMask(int x, int y, int z) {
        return masks.get(BlockPos.asLong(x, y, z));
    }

    /** Changes whenever a stored mask may have changed (blocks updated, sections cached or dropped). */
    public int version() {
        return version;
    }

    /** Stores a mask directly (tests, and blocks computed elsewhere). {@link #EMPTY} removes the entry. */
    public void putMask(int x, int y, int z, long mask) {
        version++;
        long key = BlockPos.asLong(x, y, z);
        if (mask == EMPTY) {
            masks.remove(key);
        } else {
            masks.put(key, mask);
        }
    }

    /** Number of stored non-air blocks. */
    public int size() {
        return masks.size();
    }

    public int cachedSectionCount() {
        return cachedSections.size();
    }

    public boolean isSectionCached(int sectionX, int sectionY, int sectionZ) {
        return cachedSections.contains(SectionPos.asLong(sectionX, sectionY, sectionZ));
    }

    public void clear() {
        version++;
        masks.clear();
        cachedSections.clear();
        stateMasks.clear();
    }

    /**
     * Makes sure every section a particle can reach this tick is cached: the particle's own block and the blocks
     * next to it (the solver's speed limit keeps a particle within one block per tick). Main thread only.
     */
    public void ensureCached(Level level, ParticleSoA particles) {
        long lastSection = Long.MIN_VALUE;
        for (int i = 0; i < particles.activeCount; i++) {
            int bx = particles.originX + (int) Math.floor(particles.posX[i]);
            int by = particles.originY + (int) Math.floor(particles.posY[i]);
            int bz = particles.originZ + (int) Math.floor(particles.posZ[i]);
            int sx1 = (bx + 1) >> 4;
            int sy1 = (by + 1) >> 4;
            int sz1 = (bz + 1) >> 4;
            for (int sx = (bx - 1) >> 4; sx <= sx1; sx++) {
                for (int sy = (by - 1) >> 4; sy <= sy1; sy++) {
                    for (int sz = (bz - 1) >> 4; sz <= sz1; sz++) {
                        long key = SectionPos.asLong(sx, sy, sz);
                        if (key != lastSection) {
                            lastSection = key;
                            if (!cachedSections.contains(key)) {
                                cacheSection(level, sx, sy, sz);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Reads one chunk section into the cache. Sections above or below the world count as cached air.
     *
     * @return false if the chunk is not loaded (nothing is cached then)
     */
    public boolean cacheSection(Level level, int sectionX, int sectionY, int sectionZ) {
        long sectionKey = SectionPos.asLong(sectionX, sectionY, sectionZ);
        if (cachedSections.contains(sectionKey)) {
            return true;
        }
        int index = level.getSectionIndexFromSectionY(sectionY);
        if (index < 0 || index >= level.getSectionsCount()) {
            cachedSections.add(sectionKey);
            return true;
        }
        // getChunkNow never loads or generates a chunk.
        LevelChunk chunk = level.getChunkSource().getChunkNow(sectionX, sectionZ);
        if (chunk == null) {
            return false;
        }
        cachedSections.add(sectionKey);
        // Blocks of this section read as air until now.
        version++;
        LevelChunkSection section = chunk.getSection(index);
        if (section.hasOnlyAir()) {
            return true;
        }
        int baseX = SectionPos.sectionToBlockCoord(sectionX);
        int baseY = SectionPos.sectionToBlockCoord(sectionY);
        int baseZ = SectionPos.sectionToBlockCoord(sectionZ);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (state.isAir()) {
                        continue;
                    }
                    long mask = maskOf(state, level, scratchPos.set(baseX + x, baseY + y, baseZ + z));
                    if (mask != EMPTY) {
                        masks.put(BlockPos.asLong(baseX + x, baseY + y, baseZ + z), mask);
                    }
                }
            }
        }
        return true;
    }

    /** Re-reads one block after a block update, if its section is cached. Main thread only. */
    public void onBlockChanged(Level level, BlockPos pos) {
        long sectionKey = SectionPos.asLong(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        if (cachedSections.contains(sectionKey)) {
            putMask(pos.getX(), pos.getY(), pos.getZ(), maskOf(level.getBlockState(pos), level, pos));
        }
    }

    /** Drops one section, e.g. when its chunk unloads. */
    public void invalidateSection(int sectionX, int sectionY, int sectionZ) {
        if (!cachedSections.remove(SectionPos.asLong(sectionX, sectionY, sectionZ))) {
            return;
        }
        version++;
        int baseX = SectionPos.sectionToBlockCoord(sectionX);
        int baseY = SectionPos.sectionToBlockCoord(sectionY);
        int baseZ = SectionPos.sectionToBlockCoord(sectionZ);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    masks.remove(BlockPos.asLong(baseX + x, baseY + y, baseZ + z));
                }
            }
        }
    }

    private long maskOf(BlockState state, BlockGetter level, BlockPos pos) {
        boolean positionIndependent = !state.hasOffsetFunction() && !state.getBlock().hasDynamicShape();
        if (positionIndependent && stateMasks.containsKey(state)) {
            return stateMasks.getLong(state);
        }
        long mask = computeMask(state, level, pos);
        if (positionIndependent) {
            stateMasks.put(state, mask);
        }
        return mask;
    }

    /**
     * Rasterizes a block's collision shape onto its 4 x 4 x 4 sub-voxels. A sub-voxel is solid if any collision box
     * overlaps it, so thin walls (cauldron sides, trapdoors, carpets) never leak particles.
     */
    public static long computeMask(BlockState state, BlockGetter level, BlockPos pos) {
        if (state.isAir()) {
            return EMPTY;
        }
        if (state.isCollisionShapeFullBlock(level, pos)) {
            return FULL;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return EMPTY;
        }
        long mask = EMPTY;
        for (AABB box : shape.toAabbs()) {
            int x0 = firstSubVoxel(box.minX);
            int x1 = lastSubVoxel(box.maxX);
            int y0 = firstSubVoxel(box.minY);
            int y1 = lastSubVoxel(box.maxY);
            int z0 = firstSubVoxel(box.minZ);
            int z1 = lastSubVoxel(box.maxZ);
            for (int sy = y0; sy <= y1; sy++) {
                for (int sz = z0; sz <= z1; sz++) {
                    for (int sx = x0; sx <= x1; sx++) {
                        mask |= 1L << subVoxelBit(sx, sy, sz);
                    }
                }
            }
        }
        return mask;
    }

    private static int firstSubVoxel(double min) {
        return Math.max(0, (int) Math.floor(min * 4.0 + OVERLAP_EPSILON));
    }

    private static int lastSubVoxel(double max) {
        return Math.min(3, (int) Math.ceil(max * 4.0 - OVERLAP_EPSILON) - 1);
    }
}
