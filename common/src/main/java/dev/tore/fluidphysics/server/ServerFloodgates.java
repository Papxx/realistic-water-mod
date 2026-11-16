// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.server;

import dev.tore.fluidphysics.interaction.Floodgates;
import dev.tore.fluidphysics.physics.ChunkKeys;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The {@link Floodgates} of one server level and the level access they need. Server thread only; reads blocks only in
 * loaded chunks.
 */
final class ServerFloodgates implements Floodgates.World {
    /** Source blocks converted per tick at most: 16 blocks are 1,024 particles. */
    static final int BUDGET_PER_TICK = 16;

    private final ServerLevel level;
    private final Floodgates floodgates = new Floodgates();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    ServerFloodgates(ServerLevel level) {
        this.level = level;
    }

    /**
     * Called before block {@code at} changes to {@code next}: remembers it as an opening if it held something back
     * (had a collision shape) and will not (has none).
     */
    void beforeChange(BlockPos at, BlockState next) {
        if (!level.isLoaded(at) || !next.getCollisionShape(level, at).isEmpty()) {
            return;
        }
        if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
            floodgates.opened(at.getX(), at.getY(), at.getZ());
        }
    }

    /** Converts the sources behind waiting openings, up to {@link #BUDGET_PER_TICK}. */
    void tick() {
        if (floodgates.hasPending()) {
            floodgates.run(this, BUDGET_PER_TICK);
        }
    }

    int pending() {
        return floodgates.pending();
    }

    void clear() {
        floodgates.clear();
    }

    @Override
    public boolean isOpen(long key) {
        pos.set(key);
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && !state.getFluidState().isSource();
    }

    @Override
    public int sourceMaterial(long key) {
        pos.set(key);
        if (!level.isLoaded(pos)) {
            return -1;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isSource()) {
            return -1;
        }
        if (state.is(Blocks.WATER)) {
            return ParticleSoA.MATERIAL_WATER;
        }
        return state.is(Blocks.LAVA) ? ParticleSoA.MATERIAL_LAVA : -1;
    }

    @Override
    public boolean isSimulated(long key) {
        return level.shouldTickBlocksAt(ChunkKeys.ofBlock(BlockPos.getX(key), BlockPos.getZ(key)));
    }

    @Override
    public boolean convert(long key, byte material, float vx, float vy, float vz) {
        int x = BlockPos.getX(key);
        int y = BlockPos.getY(key);
        int z = BlockPos.getZ(key);
        FluidSimulation simulation = ServerSimulations.get(level);
        if (simulation.limit() - simulation.activeCount() < ParticleSoA.PARTICLES_PER_BLOCK
                || !simulation.canSpawnAt(x, y, z)) {
            return false;
        }
        level.setBlock(pos.set(key), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        ServerSimulations.spawn(level, x, y, z, material, ParticleSoA.PARTICLES_PER_BLOCK, vx, vy, vz);
        return true;
    }
}
