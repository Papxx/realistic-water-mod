// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.block;

import com.mojang.serialization.MapCodec;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.server.ServerSimulations;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
//? if >=1.21.2 {
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
//?} else {
/*import net.minecraft.world.level.LevelAccessor;
*///?}

/**
 * Sand that crumbles into 64 sand particles instead of falling as one block (Plan.md §4.1): when the block below it
 * is gone, when a player mines it and when an explosion hits it. It drops nothing itself; the particles turn back into
 * vanilla sand once they rest (condensation).
 *
 * <p>Like vanilla sand it checks for support two ticks after it is placed or a neighbour changes. If the particle
 * limit leaves no room for its 64 particles, it stays put and tries again a second later, so no sand is lost.
 */
public class PhysicsSandBlock extends Block {
    public static final MapCodec<PhysicsSandBlock> CODEC = simpleCodec(PhysicsSandBlock::new);
    /** Ticks between losing support and crumbling, as for vanilla falling blocks. */
    private static final int CRUMBLE_DELAY = 2;
    /** Ticks until the next try when the particle limit is reached. */
    private static final int RETRY_DELAY = 20;
    /** Upward speed of particles freed by an explosion, in blocks per second (the blast pushes them further). */
    private static final float BLAST_LIFT = 2f;

    public PhysicsSandBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<PhysicsSandBlock> codec() {
        return CODEC;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        level.scheduleTick(pos, this, CRUMBLE_DELAY);
    }

    //? if >=1.21.2 {
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                     Direction direction, BlockPos neighborPos, BlockState neighborState,
                                     RandomSource random) {
        ticks.scheduleTick(pos, this, CRUMBLE_DELAY);
        return super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random);
    }
    //?} else {
    /*@Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        level.scheduleTick(pos, this, CRUMBLE_DELAY);
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }
    *///?}

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!FallingBlock.isFree(level.getBlockState(pos.below())) || pos.getY() < minY(level)) {
            return;
        }
        if (!hasRoom(level, pos)) {
            level.scheduleTick(pos, this, RETRY_DELAY);
            return;
        }
        level.removeBlock(pos, false);
        spawnSand(level, pos, 0f);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel server && !player.isCreative()) {
            if (hasRoom(server, pos)) {
                spawnSand(server, pos, 0f);
            } else {
                // No room for particles: keep the sand as an item rather than losing it.
                popResource(level, pos, new ItemStack(this));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    //? if >=1.21.2 {
    @Override
    public void wasExploded(ServerLevel level, BlockPos pos, Explosion explosion) {
        if (hasRoom(level, pos)) {
            spawnSand(level, pos, BLAST_LIFT);
        }
        super.wasExploded(level, pos, explosion);
    }
    //?} else {
    /*@Override
    public void wasExploded(Level level, BlockPos pos, Explosion explosion) {
        if (level instanceof ServerLevel server && hasRoom(server, pos)) {
            spawnSand(server, pos, BLAST_LIFT);
        }
        super.wasExploded(level, pos, explosion);
    }
    *///?}

    /** Whether the level's simulation can take all 64 particles of this block. */
    private static boolean hasRoom(ServerLevel level, BlockPos pos) {
        FluidSimulation simulation = ServerSimulations.get(level);
        return simulation.canSpawnAt(pos.getX(), pos.getY(), pos.getZ())
                && simulation.limit() - simulation.activeCount() >= ParticleSoA.PARTICLES_PER_BLOCK;
    }

    private static void spawnSand(ServerLevel level, BlockPos pos, float upward) {
        ServerSimulations.spawn(level, pos.getX(), pos.getY(), pos.getZ(), ParticleSoA.MATERIAL_SAND,
                ParticleSoA.PARTICLES_PER_BLOCK, 0f, upward, 0f);
    }

    private static int minY(Level level) {
        //? if >=1.21.2 {
        return level.getMinY();
        //?} else {
        /*return level.getMinBuildHeight();
        *///?}
    }
}
