// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.block;

import com.mojang.serialization.MapCodec;
import dev.tore.fluidphysics.interaction.LavaLight;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * An invisible block that glows where lava particles rest. It counts as air (no collision, replaceable, never drops),
 * is placed by {@link LavaLight} and removes itself a second after the lava is gone. Because it keeps its own scheduled
 * tick, light blocks left behind by a crash disappear the next time their chunk ticks.
 */
public class PhysicsLightBlock extends Block {
    public static final MapCodec<PhysicsLightBlock> CODEC = simpleCodec(PhysicsLightBlock::new);
    /** Ticks between checks whether the lava is still there. */
    public static final int CHECK_INTERVAL = 20;

    public PhysicsLightBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<PhysicsLightBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        level.scheduleTick(pos, this, CHECK_INTERVAL);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (LavaLight.keeps(level, pos)) {
            level.scheduleTick(pos, this, CHECK_INTERVAL);
        } else {
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }
}
