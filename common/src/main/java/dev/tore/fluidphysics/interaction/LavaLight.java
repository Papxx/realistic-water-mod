// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import dev.tore.fluidphysics.physics.BlockOccupancy;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.registry.ModRegistry;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Makes resting lava particles shine (CLAUDE.md §3.3). Minecraft's light engine only knows blocks, so a block that holds
 * enough resting lava particles gets an invisible light block ({@code PhysicsLightBlock}). At most one is placed per
 * 2 x 2 x 2 cell, which keeps the work for the light engine small; moving lava is not lit, it would flicker.
 */
public final class LavaLight {
    /** Resting lava particles a block needs before it is lit. */
    static final int MIN_RESTING = 16;
    /** Most light blocks placed per run. */
    static final int MAX_PER_RUN = 32;

    private static final LongOpenHashSet CELLS = new LongOpenHashSet();
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    private LavaLight() {
    }

    /** Whether the lava that lit {@code pos} is still resting there or next to it. */
    public static boolean keeps(ServerLevel level, BlockPos pos) {
        FluidSimulation simulation = Simulations.forLevel(level);
        BlockOccupancy occupancy = simulation == null ? null : simulation.occupancy();
        if (occupancy == null) {
            return false;
        }
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int slot = occupancy.slotOf(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (slot >= 0 && occupancy.resting(slot, ParticleSoA.MATERIAL_LAVA) >= MIN_RESTING / 2) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Lights the blocks that hold resting lava; call about once a second on the server thread. */
    public static void place(ServerLevel level, FluidSimulation simulation) {
        BlockOccupancy occupancy = simulation.occupancy();
        if (occupancy == null) {
            return;
        }
        CELLS.clear();
        int placed = 0;
        for (int slot = 0; slot < occupancy.size() && placed < MAX_PER_RUN; slot++) {
            if (occupancy.resting(slot, ParticleSoA.MATERIAL_LAVA) < MIN_RESTING) {
                continue;
            }
            POS.set(occupancy.key(slot));
            long cell = BlockPos.asLong(POS.getX() >> 1, POS.getY() >> 1, POS.getZ() >> 1);
            if (!CELLS.add(cell) || !level.isLoaded(POS)) {
                continue;
            }
            // Lava particles are in the air; the light goes into the block itself, or the one above.
            for (int dy = 0; dy <= 1; dy++) {
                BlockState state = level.getBlockState(POS);
                if (state.is(ModRegistry.physicsLight())) {
                    break;
                }
                if (state.is(Blocks.AIR) || state.is(Blocks.CAVE_AIR)) {
                    level.setBlock(POS, ModRegistry.physicsLight().defaultBlockState(), Block.UPDATE_CLIENTS);
                    placed++;
                    break;
                }
                POS.move(0, 1, 0);
            }
        }
    }
}
