// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.client;

import dev.tore.fluidphysics.network.FluidCondensePayload;
import dev.tore.fluidphysics.network.FluidImpulsePayload;
import dev.tore.fluidphysics.network.FluidSpawnPayload;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.physics.WorkerPool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * The client's own simulation of the level it shows. It is driven only by the server's events (spawn, impulse,
 * condense, clear); particle positions never cross the network.
 *
 * <p>Client classes: only the loaders' client entry points and client payload handlers may touch this class, so it is
 * never loaded on a dedicated server. Render thread only (both loaders run the payload handlers there).
 */
public final class ClientSimulation {
    /** Default particle limit on the client (CLAUDE.md §3.1). */
    public static final int DEFAULT_LIMIT = ParticleSoA.MAX_PARTICLES;

    private static WorkerPool pool;
    private static FluidSimulation simulation;
    /** The level {@link #simulation} belongs to; a new level (dimension change, disconnect) drops it. */
    private static ClientLevel level;

    private ClientSimulation() {
    }

    /** The simulation of the current level, or null before the first event. For the renderer and debug output. */
    public static FluidSimulation current() {
        return simulation;
    }

    /**
     * The simulation of {@code shown}, or null if there is none yet or it still belongs to the previous level (a frame
     * can be drawn after a dimension change and before the next tick resets the simulation).
     */
    public static FluidSimulation currentFor(ClientLevel shown) {
        return shown != null && shown == level ? simulation : null;
    }

    /** Advances the client particles by one tick; call at the end of every client tick. */
    public static void tick(Minecraft minecraft) {
        ClientLevel current = minecraft.level;
        if (current != level) {
            reset(current);
        }
        // The game pauses in singleplayer menus and with /tick freeze; the particles pause with it.
        if (simulation == null || current == null || minecraft.isPaused()
                || !current.tickRateManager().runsNormally()) {
            return;
        }
        simulation.tick(current);
    }

    public static void onSpawn(FluidSpawnPayload payload) {
        FluidSimulation sim = forCurrentLevel();
        if (sim != null) {
            long pos = payload.packedBlockPos();
            sim.spawn(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos), payload.materialType(),
                    payload.count(), payload.dirX(), payload.dirY(), payload.dirZ());
        }
    }

    public static void onImpulse(FluidImpulsePayload payload) {
        FluidSimulation sim = forCurrentLevel();
        if (sim != null) {
            sim.applyImpulse(payload.x(), payload.y(), payload.z(), payload.radius(), payload.force());
        }
    }

    public static void onCondense(FluidCondensePayload payload) {
        FluidSimulation sim = forCurrentLevel();
        if (sim != null) {
            long pos = payload.packedBlockPos();
            sim.removeNear(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos), payload.materialType(),
                    payload.removedCount());
        }
    }

    public static void onClear() {
        FluidSimulation sim = forCurrentLevel();
        if (sim != null) {
            sim.clear();
        }
    }

    /** The simulation of the level the client shows right now, created on first use; null without a level. */
    private static FluidSimulation forCurrentLevel() {
        ClientLevel current = Minecraft.getInstance().level;
        if (current != level) {
            reset(current);
        }
        if (current == null) {
            return null;
        }
        if (simulation == null) {
            if (pool == null) {
                pool = new WorkerPool(WorkerPool.defaultSize());
            }
            simulation = new FluidSimulation(pool, DEFAULT_LIMIT);
        }
        return simulation;
    }

    /** Forgets the particles of the old level. The worker threads stay parked for the next world. */
    private static void reset(ClientLevel newLevel) {
        simulation = null;
        level = newLevel;
    }
}
