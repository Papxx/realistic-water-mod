// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.server;

import dev.tore.fluidphysics.network.FluidClearPayload;
import dev.tore.fluidphysics.network.FluidCondensePayload;
import dev.tore.fluidphysics.network.FluidImpulsePayload;
import dev.tore.fluidphysics.network.FluidNetworking;
import dev.tore.fluidphysics.network.FluidSpawnPayload;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.physics.WorkerPool;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The server's simulations, one per level, and the server-side entry points for everything that changes particles.
 * Each change is applied to the server's simulation and sent as an event to the clients that can see it, which apply
 * it to their own simulations.
 *
 * <p>Server thread only. The loader modules call {@link #tick} after each level tick and {@link #onServerStopped} when
 * the server stops.
 */
public final class ServerSimulations {
    /** Default particle limit per level on a server (CLAUDE.md §3.1). */
    public static final int DEFAULT_LIMIT = 8192;

    private static final Reference2ObjectOpenHashMap<ServerLevel, FluidSimulation> SIMULATIONS =
            new Reference2ObjectOpenHashMap<>();
    /** Shared by all levels: they tick one after another on the server thread. */
    private static WorkerPool pool;
    private static int limit = DEFAULT_LIMIT;

    private ServerSimulations() {
    }

    /** The simulation of {@code level}, created on first use (it allocates no particle arrays until a spawn). */
    public static FluidSimulation get(ServerLevel level) {
        FluidSimulation simulation = SIMULATIONS.get(level);
        if (simulation == null) {
            if (pool == null) {
                pool = new WorkerPool(WorkerPool.defaultSize());
            }
            simulation = new FluidSimulation(pool, limit);
            SIMULATIONS.put(level, simulation);
        }
        return simulation;
    }

    /** The simulation of {@code level}, or null if none was created yet. */
    public static FluidSimulation getIfPresent(ServerLevel level) {
        return SIMULATIONS.get(level);
    }

    /** Particles in all levels. */
    public static int totalParticles() {
        int total = 0;
        for (FluidSimulation simulation : SIMULATIONS.values()) {
            total += simulation.activeCount();
        }
        return total;
    }

    /** Advances the particles of {@code level} by one tick; call after the level ticked. */
    public static void tick(ServerLevel level) {
        FluidSimulation simulation = SIMULATIONS.get(level);
        // /tick freeze stops the level, so it stops the particles too.
        if (simulation != null && level.tickRateManager().runsNormally()) {
            simulation.tick(level);
        }
    }

    /** Drops all simulations and stops the solver threads, so the next server (another world) starts fresh. */
    public static void onServerStopped() {
        SIMULATIONS.clear();
        limit = DEFAULT_LIMIT;
        if (pool != null) {
            pool.close();
            pool = null;
        }
    }

    public static int limit() {
        return limit;
    }

    /** Sets the particle limit of every level until the server stops, clamped to {@code 0..MAX_PARTICLES}. */
    public static void setLimit(int newLimit) {
        limit = Math.max(0, Math.min(newLimit, ParticleSoA.MAX_PARTICLES));
        for (FluidSimulation simulation : SIMULATIONS.values()) {
            simulation.setLimit(limit);
        }
    }

    /** Whether any level holds more particles than the limit (possible right after lowering it). */
    public static boolean anyAboveLimit() {
        for (FluidSimulation simulation : SIMULATIONS.values()) {
            if (simulation.activeCount() > limit) {
                return true;
            }
        }
        return false;
    }

    /**
     * Spawns up to {@code count} particles (at most 64) in one block and tells the clients that see it.
     *
     * @return the number of particles spawned, see {@link FluidSimulation#spawn}
     */
    public static int spawn(ServerLevel level, int blockX, int blockY, int blockZ, byte material, int count,
                            float vx, float vy, float vz) {
        int spawned = get(level).spawn(blockX, blockY, blockZ, material, count, vx, vy, vz);
        if (spawned > 0) {
            FluidNetworking.sendToTracking(level, blockX, blockZ, new FluidSpawnPayload(
                    BlockPos.asLong(blockX, blockY, blockZ), material, (short) spawned, vx, vy, vz));
        }
        return spawned;
    }

    /**
     * Pushes the particles around a point outward and tells the clients that see it.
     *
     * @return the number of server particles pushed, see {@link FluidSimulation#applyImpulse}
     */
    public static int impulse(ServerLevel level, double x, double y, double z, float radius, float force) {
        FluidSimulation simulation = SIMULATIONS.get(level);
        if (simulation == null || simulation.activeCount() == 0) {
            return 0;
        }
        int pushed = simulation.applyImpulse(x, y, z, radius, force);
        // Sent even if no server particle was hit: a client's particles may have drifted into the radius.
        FluidNetworking.sendToTracking(level, (int) Math.floor(x), (int) Math.floor(z),
                new FluidImpulsePayload((float) x, (float) y, (float) z, radius, force));
        return pushed;
    }

    /**
     * Removes up to {@code count} particles of one material from a block (they condensed into a vanilla block or
     * filled a cauldron) and tells the clients that see it.
     *
     * @return the number of particles removed, see {@link FluidSimulation#removeNear}
     */
    public static int condense(ServerLevel level, int blockX, int blockY, int blockZ, byte material, int count) {
        FluidSimulation simulation = SIMULATIONS.get(level);
        if (simulation == null) {
            return 0;
        }
        int removed = simulation.removeNear(blockX, blockY, blockZ, material, count);
        if (removed > 0) {
            FluidNetworking.sendToTracking(level, blockX, blockZ, new FluidCondensePayload(
                    BlockPos.asLong(blockX, blockY, blockZ), material, (short) removed));
        }
        return removed;
    }

    /**
     * Removes all particles of {@code level} and tells every player in it.
     *
     * @return the number of server particles removed
     */
    public static int clear(ServerLevel level) {
        FluidSimulation simulation = SIMULATIONS.get(level);
        int removed = simulation == null ? 0 : simulation.clear();
        // Sent even if the server had none: clients may hold particles that drifted apart from the server's.
        FluidNetworking.sendToLevel(level, FluidClearPayload.INSTANCE);
        return removed;
    }
}
