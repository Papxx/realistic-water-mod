// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.interaction;

import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.server.ServerSimulations;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Finds the particle simulation of a level on either side, for code that runs on both (mixins, items, blocks).
 *
 * <p>The client side is reached through a lookup the client entry point installs, so common code never references
 * client classes and a dedicated server never loads them.
 */
public final class Simulations {
    private static volatile Function<Level, FluidSimulation> clientLookup = level -> null;

    private Simulations() {
    }

    /**
     * Installs the client lookup. It must return null for any level other than the one the client simulates, and
     * when called from another thread than the client's main thread.
     */
    public static void setClientLookup(Function<Level, FluidSimulation> lookup) {
        clientLookup = lookup;
    }

    /**
     * The simulation of {@code level}, or null if it has none or is not owned by the calling thread. Never creates a
     * simulation.
     */
    public static FluidSimulation forLevel(Level level) {
        if (level instanceof ServerLevel server) {
            return server.getServer().isSameThread() ? ServerSimulations.getIfPresent(server) : null;
        }
        return clientLookup.apply(level);
    }

    /**
     * Called after every successful {@code Level.setBlock} on both sides: re-reads the block into the simulation's
     * voxel cache, so particles see placed and removed blocks at once (Plan.md §7.3).
     */
    public static void onBlockChanged(Level level, BlockPos pos) {
        FluidSimulation simulation = forLevel(level);
        if (simulation != null) {
            simulation.voxels().onBlockChanged(level, pos);
        }
    }
}
