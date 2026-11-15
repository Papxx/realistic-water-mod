// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ChunkVoxelCacheTest {
    private static final long LOWER_HALF = 0x0000_0000_FFFF_FFFFL;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void bitLayoutPutsLayersInOrder() {
        assertEquals(0, ChunkVoxelCache.subVoxelBit(0, 0, 0));
        assertEquals(3, ChunkVoxelCache.subVoxelBit(3, 0, 0));
        assertEquals(12, ChunkVoxelCache.subVoxelBit(0, 0, 3));
        assertEquals(16, ChunkVoxelCache.subVoxelBit(0, 1, 0));
        assertEquals(63, ChunkVoxelCache.subVoxelBit(3, 3, 3));
    }

    @Test
    void unknownBlocksReadAsAir() {
        ChunkVoxelCache cache = new ChunkVoxelCache();
        cache.putMask(5, -3, 7, ChunkVoxelCache.FULL);

        assertEquals(ChunkVoxelCache.FULL, cache.getMask(5, -3, 7));
        assertEquals(ChunkVoxelCache.EMPTY, cache.getMask(5, -2, 7));

        cache.putMask(5, -3, 7, ChunkVoxelCache.EMPTY);
        assertEquals(0, cache.size());
    }

    @Test
    void simpleShapes() {
        assertEquals(ChunkVoxelCache.FULL, mask(Blocks.STONE.defaultBlockState()));
        assertEquals(ChunkVoxelCache.EMPTY, mask(Blocks.AIR.defaultBlockState()));
        assertEquals(ChunkVoxelCache.EMPTY, mask(Blocks.WATER.defaultBlockState()));
        assertEquals(LOWER_HALF, mask(Blocks.OAK_SLAB.defaultBlockState()));
        assertEquals(~LOWER_HALF, mask(Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)));
        // A closed trapdoor is 3/16 thick: one solid layer, so particles can not leak through it.
        assertEquals(0xFFFFL, mask(Blocks.OAK_TRAPDOOR.defaultBlockState()));
    }

    @Test
    void cauldronIsAHollowBowl() {
        long cauldron = mask(Blocks.CAULDRON.defaultBlockState());
        for (int sx = 0; sx < 4; sx++) {
            for (int sz = 0; sz < 4; sz++) {
                assertTrue(ChunkVoxelCache.isSolid(cauldron, sx, 0, sz), "floor must be solid");
                boolean inner = sx >= 1 && sx <= 2 && sz >= 1 && sz <= 2;
                for (int sy = 1; sy < 4; sy++) {
                    assertEquals(!inner, ChunkVoxelCache.isSolid(cauldron, sx, sy, sz),
                            "sub-voxel " + sx + "," + sy + "," + sz);
                }
            }
        }
        assertFalse(ChunkVoxelCache.isSolid(cauldron, 1, 3, 1));
    }

    @Test
    void fluidAndCauldronTags() {
        assertEquals(ChunkVoxelCache.TAG_WATER | ChunkVoxelCache.TAG_SOURCE,
                ChunkVoxelCache.computeTag(Blocks.WATER.defaultBlockState()));
        assertEquals(ChunkVoxelCache.TAG_WATER,
                ChunkVoxelCache.computeTag(Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3)));
        assertEquals(ChunkVoxelCache.TAG_LAVA | ChunkVoxelCache.TAG_SOURCE,
                ChunkVoxelCache.computeTag(Blocks.LAVA.defaultBlockState()));
        assertEquals(ChunkVoxelCache.TAG_WATER | ChunkVoxelCache.TAG_SOURCE, ChunkVoxelCache.computeTag(
                Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED, true)));
        assertEquals(ChunkVoxelCache.TAG_CAULDRON, ChunkVoxelCache.computeTag(Blocks.CAULDRON.defaultBlockState()));
        assertEquals(ChunkVoxelCache.TAG_CAULDRON,
                ChunkVoxelCache.computeTag(Blocks.WATER_CAULDRON.defaultBlockState()));
        assertEquals(0, ChunkVoxelCache.computeTag(Blocks.STONE.defaultBlockState()));
    }

    @Test
    void sourceBlocksAreSolidOnlyForTheirOwnFluid() {
        ChunkVoxelCache cache = new ChunkVoxelCache();
        cache.putTag(0, 0, 0, ChunkVoxelCache.TAG_WATER | ChunkVoxelCache.TAG_SOURCE);
        cache.putTag(1, 0, 0, ChunkVoxelCache.TAG_WATER);
        cache.putTag(2, 0, 0, ChunkVoxelCache.TAG_LAVA | ChunkVoxelCache.TAG_SOURCE);
        cache.putMask(3, 0, 0, LOWER_HALF);
        cache.putTag(3, 0, 0, ChunkVoxelCache.TAG_WATER | ChunkVoxelCache.TAG_SOURCE);

        assertEquals(ChunkVoxelCache.FULL, cache.getMask(0, 0, 0, ChunkVoxelCache.CLASS_WATER));
        assertEquals(ChunkVoxelCache.EMPTY, cache.getMask(0, 0, 0, ChunkVoxelCache.CLASS_LAVA));
        assertEquals(ChunkVoxelCache.EMPTY, cache.getMask(0, 0, 0, ChunkVoxelCache.CLASS_SOLID));
        assertEquals(ChunkVoxelCache.EMPTY, cache.getMask(1, 0, 0, ChunkVoxelCache.CLASS_WATER));
        assertEquals(ChunkVoxelCache.FULL, cache.getMask(2, 0, 0, ChunkVoxelCache.CLASS_LAVA));
        assertEquals(ChunkVoxelCache.EMPTY, cache.getMask(2, 0, 0, ChunkVoxelCache.CLASS_WATER));
        // A waterlogged slab: full for water, the slab shape for everything else.
        assertEquals(ChunkVoxelCache.FULL, cache.getMask(3, 0, 0, ChunkVoxelCache.CLASS_WATER));
        assertEquals(LOWER_HALF, cache.getMask(3, 0, 0, ChunkVoxelCache.CLASS_SOLID));
    }

    @Test
    void forcedEmptyBlocksReadAsAir() {
        ChunkVoxelCache cache = new ChunkVoxelCache();
        cache.putMask(4, 5, 6, ChunkVoxelCache.FULL);
        int version = cache.version();
        cache.forceEmpty(4, 5, 6);
        assertEquals(ChunkVoxelCache.EMPTY, cache.getMask(4, 5, 6));
        assertTrue(cache.version() != version, "solvers must drop the old mask");
    }

    private static long mask(BlockState state) {
        return ChunkVoxelCache.computeMask(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }
}
