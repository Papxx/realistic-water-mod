// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Solid world geometry around the particles, one {@code long} per block: each of the 64 bits marks one
 * 0.25-block sub-voxel of the block's collision shape as solid (see {@link #subVoxelBit}).
 *
 * <p>Besides the shape, each block may carry a {@link #getTag tag}: whether it holds vanilla water or lava (and whether
 * that is a source) and whether it is a cauldron. Interactions read the tags instead of the level, and the solver uses
 * them for {@link #getMask(int, int, int, int) collision classes}: water particles rest on water source blocks and
 * lava particles on lava source blocks as if those were solid.
 *
 * <p>The solver only calls {@link #getMask}, which never touches the level. The level is read on the main thread,
 * a whole chunk section (16 x 16 x 16 blocks) at a time, by {@link #ensureCached} before the solver runs and by
 * {@link #onBlockChanged} when a block changes. Blocks that are not stored read as air: that covers real air,
 * fluids, and sections that are not cached (e.g. in unloaded chunks), so callers must not simulate particles in
 * uncached sections.
 *
 * <p>Each chunk column remembers the {@link LevelChunk} its sections were read from. {@link #ensureCached} first drops
 * the columns whose chunk is gone or was replaced (unloaded, or sent again to a client), so the cache never keeps
 * blocks of an unloaded chunk or misses changes made while a client did not see the chunk (Plan.md §7.5).
 *
 * <p>Threading: {@link #getMask} may be called from several solver threads at once, but only while nothing changes
 * the cache; all other methods belong to the main thread and must not run during a solver step.
 */
public final class ChunkVoxelCache {
    public static final long EMPTY = 0L;
    public static final long FULL = -1L;

    /** {@link #getTag Tag} bit: the block holds vanilla water (source, flowing or waterlogged). */
    public static final byte TAG_WATER = 1;
    /** Tag bit: the block holds vanilla lava. */
    public static final byte TAG_LAVA = 2;
    /** Tag bit, together with {@link #TAG_WATER} or {@link #TAG_LAVA}: the fluid is a source. */
    public static final byte TAG_SOURCE = 4;
    /** Tag bit: the block is a cauldron (empty or filled). */
    public static final byte TAG_CAULDRON = 8;

    /** Collision class of particles that only collide with solid shapes (slime, sand). */
    public static final int CLASS_SOLID = 0;
    /** Collision class of water particles: water source blocks count as solid. Equal to {@link #TAG_WATER}. */
    public static final int CLASS_WATER = TAG_WATER;
    /** Collision class of lava particles: lava source blocks count as solid. Equal to {@link #TAG_LAVA}. */
    public static final int CLASS_LAVA = TAG_LAVA;
    /** Number of collision classes. */
    public static final int CLASS_COUNT = 3;

    /** Overlap (in sub-voxel units) a collision box needs before a sub-voxel counts as solid. */
    private static final double OVERLAP_EPSILON = 1.0e-3;

    /** Packed block position ({@link BlockPos#asLong(int, int, int)}) to sub-voxel mask; absent means air. */
    private final Long2LongOpenHashMap masks = new Long2LongOpenHashMap();
    /** Packed block position to {@link #getTag tag}; absent means 0. */
    private final Long2ByteOpenHashMap tags = new Long2ByteOpenHashMap();
    /**
     * Blocks that read as air although the level still says otherwise, until the level reports a change there (see
     * {@link #forceEmpty}).
     */
    private final LongOpenHashSet forcedEmpty = new LongOpenHashSet();
    /** Sections ({@link SectionPos#asLong(int, int, int)}) whose blocks are all in {@link #masks}. */
    private final LongOpenHashSet cachedSections = new LongOpenHashSet();
    /** Chunk columns ({@link ChunkKeys}) with sections read from a loaded chunk. */
    private final Long2ObjectOpenHashMap<Column> columns = new Long2ObjectOpenHashMap<>();
    /** The values of {@link #columns}, for iterating without an iterator. */
    private final ObjectArrayList<Column> columnList = new ObjectArrayList<>();
    /** Masks of block states whose collision shape does not depend on the position. */
    private final Reference2LongOpenHashMap<BlockState> stateMasks = new Reference2LongOpenHashMap<>();
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();
    /** Bumped whenever any stored mask may have changed, so solvers know to drop masks they remembered. */
    private int version;

    public ChunkVoxelCache() {
        masks.defaultReturnValue(EMPTY);
        tags.defaultReturnValue((byte) 0);
    }

    /** A chunk column with cached sections: the chunk they were read from and the range of their section ys. */
    private static final class Column {
        final int x;
        final int z;
        final LevelChunk chunk;
        int minSectionY = Integer.MAX_VALUE;
        int maxSectionY = Integer.MIN_VALUE;

        Column(int x, int z, LevelChunk chunk) {
            this.x = x;
            this.z = z;
            this.chunk = chunk;
        }
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

    /**
     * Sub-voxel mask of the block as seen by particles of one collision class: {@link #FULL} for a source block of
     * the class's own fluid ({@link #CLASS_WATER}, {@link #CLASS_LAVA}), the solid shape otherwise.
     */
    public long getMask(int x, int y, int z, int collisionClass) {
        long key = BlockPos.asLong(x, y, z);
        long mask = masks.get(key);
        if (collisionClass != CLASS_SOLID && mask != FULL) {
            int tag = tags.get(key);
            if ((tag & TAG_SOURCE) != 0 && (tag & collisionClass) != 0) {
                return FULL;
            }
        }
        return mask;
    }

    /** {@link #TAG_WATER Tag bits} of the block at world block coordinates; 0 if none or unknown. */
    public int getTag(int x, int y, int z) {
        return tags.get(BlockPos.asLong(x, y, z));
    }

    /** Stores a tag directly (tests). 0 removes the entry. */
    public void putTag(int x, int y, int z, int tag) {
        version++;
        long key = BlockPos.asLong(x, y, z);
        if (tag == 0) {
            tags.remove(key);
        } else {
            tags.put(key, (byte) tag);
        }
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

    /** Number of chunk columns with sections read from a loaded chunk. */
    public int cachedColumnCount() {
        return columnList.size();
    }

    public boolean isSectionCached(int sectionX, int sectionY, int sectionZ) {
        return cachedSections.contains(SectionPos.asLong(sectionX, sectionY, sectionZ));
    }

    public void clear() {
        version++;
        masks.clear();
        tags.clear();
        forcedEmpty.clear();
        cachedSections.clear();
        columns.clear();
        columnList.clear();
        stateMasks.clear();
    }

    /**
     * Makes a block read as air until the level reports a change there, whatever the level says now. A client calls
     * this when the server spawns particles in a block whose removal has not reached the client yet (the spawn event
     * can overtake the block update), so the particles are not pushed out of a block that is already gone.
     */
    public void forceEmpty(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        forcedEmpty.add(key);
        if (masks.remove(key) != EMPTY) {
            version++;
        }
    }

    /**
     * Makes sure every section a particle can reach this tick is cached: the particle's own block and the blocks
     * next to it (the solver's speed limit keeps a particle within one block per tick). Main thread only.
     */
    public void ensureCached(Level level, ParticleSoA particles) {
        dropStaleColumns(level);
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
        Column column = columns.get(ChunkKeys.pack(sectionX, sectionZ));
        if (column != null && column.chunk != chunk) {
            // The chunk was loaded again since the column was read; nothing read from the old one is valid.
            dropColumn(column);
            column = null;
        }
        if (column == null) {
            column = new Column(sectionX, sectionZ, chunk);
            columns.put(ChunkKeys.pack(sectionX, sectionZ), column);
            columnList.add(column);
        }
        column.minSectionY = Math.min(column.minSectionY, sectionY);
        column.maxSectionY = Math.max(column.maxSectionY, sectionY);
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
                    long key = BlockPos.asLong(baseX + x, baseY + y, baseZ + z);
                    long mask = maskOf(state, level, scratchPos.set(baseX + x, baseY + y, baseZ + z));
                    if (mask != EMPTY && !forcedEmpty.contains(key)) {
                        masks.put(key, mask);
                    }
                    byte tag = computeTag(state);
                    if (tag != 0) {
                        tags.put(key, tag);
                    }
                }
            }
        }
        return true;
    }

    /**
     * Re-reads one block after a block update, if its section is cached. Main thread only.
     *
     * @return whether the block's mask or tag changed
     */
    public boolean onBlockChanged(Level level, BlockPos pos) {
        long key = pos.asLong();
        forcedEmpty.remove(key);
        long sectionKey = SectionPos.asLong(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        if (!cachedSections.contains(sectionKey)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        long mask = maskOf(state, level, pos);
        byte tag = computeTag(state);
        // Flowing water changes its level all the time without changing mask or tag; only real changes make the
        // solvers drop their remembered masks.
        if (mask == masks.get(key) && tag == tags.get(key)) {
            return false;
        }
        version++;
        if (mask == EMPTY) {
            masks.remove(key);
        } else {
            masks.put(key, mask);
        }
        if (tag == 0) {
            tags.remove(key);
        } else {
            tags.put(key, tag);
        }
        return true;
    }

    /**
     * Drops every column whose chunk is no longer loaded, or was loaded again as a new chunk object, since its
     * sections were read. Main thread only; allocates nothing.
     *
     * @return the number of columns dropped
     */
    public int dropStaleColumns(Level level) {
        int dropped = 0;
        for (int c = columnList.size() - 1; c >= 0; c--) {
            Column column = columnList.get(c);
            if (level.getChunkSource().getChunkNow(column.x, column.z) != column.chunk) {
                dropColumn(column);
                dropped++;
            }
        }
        return dropped;
    }

    /** Forgets a column: its sections and the column entry. */
    private void dropColumn(Column column) {
        for (int sy = column.minSectionY; sy <= column.maxSectionY; sy++) {
            invalidateSection(column.x, sy, column.z);
        }
        columns.remove(ChunkKeys.pack(column.x, column.z));
        int index = columnList.indexOf(column);
        int last = columnList.size() - 1;
        columnList.set(index, columnList.get(last));
        columnList.remove(last);
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
                    long key = BlockPos.asLong(baseX + x, baseY + y, baseZ + z);
                    masks.remove(key);
                    tags.remove(key);
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

    /** The {@link #TAG_WATER tag bits} of a block state. Does not depend on tags or the position. */
    public static byte computeTag(BlockState state) {
        int tag = state.getBlock() instanceof AbstractCauldronBlock ? TAG_CAULDRON : 0;
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            Fluid type = fluid.getType();
            if (type.isSame(Fluids.WATER)) {
                tag |= TAG_WATER;
            } else if (type.isSame(Fluids.LAVA)) {
                tag |= TAG_LAVA;
            }
            if ((tag & (TAG_WATER | TAG_LAVA)) != 0 && fluid.isSource()) {
                tag |= TAG_SOURCE;
            }
        }
        return (byte) tag;
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
