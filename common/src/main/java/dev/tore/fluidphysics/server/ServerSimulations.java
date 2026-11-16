// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.server;

import dev.tore.fluidphysics.FluidPhysicsCommon;
import dev.tore.fluidphysics.network.FluidClearPayload;
import dev.tore.fluidphysics.network.FluidCondensePayload;
import dev.tore.fluidphysics.network.FluidImpulsePayload;
import dev.tore.fluidphysics.network.FluidNetworking;
import dev.tore.fluidphysics.network.FluidSpawnPayload;
import dev.tore.fluidphysics.physics.AllocationMeter;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.physics.WorkerPool;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The server's simulations, one per level, and the server-side entry points for everything that changes particles.
 * Each change is applied to the server's simulation and sent as an event to the clients that can see it, which apply
 * it to their own simulations.
 *
 * <p>Server thread only. The loader modules call {@link #tick} after each level tick, {@link #onServerStopping} when the
 * server starts to stop and {@link #onServerStopped} when it has stopped.
 */
public final class ServerSimulations {
    /** Default particle limit per level on a server (CLAUDE.md §3.1). */
    public static final int DEFAULT_LIMIT = 8192;

    private static final Reference2ObjectOpenHashMap<ServerLevel, FluidSimulation> SIMULATIONS =
            new Reference2ObjectOpenHashMap<>();
    private static final Reference2ObjectOpenHashMap<ServerLevel, ServerInteractions> INTERACTIONS =
            new Reference2ObjectOpenHashMap<>();
    /** Floodgates per level, created when the first opening is reported (only while they are enabled). */
    private static final Reference2ObjectOpenHashMap<ServerLevel, ServerFloodgates> FLOODGATES =
            new Reference2ObjectOpenHashMap<>();
    /** Particles removed by {@link #removeMarked}, per material: block to count, and the blocks in order. */
    private static final Long2IntOpenHashMap[] REMOVED = new Long2IntOpenHashMap[ParticleSoA.MATERIAL_COUNT];
    private static final LongArrayList[] REMOVED_BLOCKS = new LongArrayList[ParticleSoA.MATERIAL_COUNT];
    private static final FluidSimulation.RemovalSink REMOVAL_SINK = (x, y, z, material) -> {
        long key = BlockPos.asLong(x, y, z);
        if (REMOVED[material].addTo(key, 1) == 0) {
            REMOVED_BLOCKS[material].add(key);
        }
    };

    static {
        for (int m = 0; m < ParticleSoA.MATERIAL_COUNT; m++) {
            REMOVED[m] = new Long2IntOpenHashMap();
            REMOVED_BLOCKS[m] = new LongArrayList();
        }
    }
    /** Shared by all levels: they tick one after another on the server thread. */
    private static WorkerPool pool;
    private static int limit = DEFAULT_LIMIT;
    /** Whether removed blocks turn the vanilla sources behind them into particles (Plan.md §7.3.3); off by default. */
    private static boolean floodgates;

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
            INTERACTIONS.put(level, new ServerInteractions(level));
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
        ServerFloodgates gates = FLOODGATES.get(level);
        if (gates != null && level.tickRateManager().runsNormally()) {
            // Before the simulation: it may create it.
            gates.tick();
        }
        FluidSimulation simulation = SIMULATIONS.get(level);
        if (simulation == null) {
            return;
        }
        ServerInteractions interactions = INTERACTIONS.get(level);
        // /tick freeze stops the level, so it stops the particles too.
        if (!level.tickRateManager().runsNormally()) {
            // Chunks still load and unload while frozen: keep the particles inside the simulated area.
            interactions.guardChunks(simulation);
            return;
        }
        // The simulation records statistics only for ticks with particles; the interactions join that record.
        boolean recorded = simulation.activeCount() > 0;
        simulation.tick(level);
        long start = System.nanoTime();
        long allocatedBefore = AllocationMeter.currentThreadBytes();
        interactions.guardChunks(simulation);
        interactions.tick(simulation);
        if (recorded) {
            simulation.stats().addInteractions(System.nanoTime() - start,
                    AllocationMeter.currentThreadBytes() - allocatedBefore);
        }
    }

    /** Lava particles cooling into stone that has not formed yet, in {@code level}. */
    public static int stoneForming(ServerLevel level) {
        ServerInteractions interactions = INTERACTIONS.get(level);
        return interactions == null ? 0 : interactions.stoneForming();
    }

    /** Chunks of {@code level} kept loaded because they hold particles. */
    public static int ticketCount(ServerLevel level) {
        ServerInteractions interactions = INTERACTIONS.get(level);
        return interactions == null ? 0 : interactions.ticketCount();
    }

    /**
     * Turns the particles of every level into vanilla blocks (Plan.md §7.5) and releases their chunk tickets. Call when
     * the server starts to stop, before the worlds are saved, so the mass survives the restart.
     */
    public static void onServerStopping(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            FluidSimulation simulation = SIMULATIONS.get(level);
            if (simulation == null) {
                continue;
            }
            int particles = simulation.activeCount();
            int blocks = INTERACTIONS.get(level).condenseAll(simulation);
            if (particles > 0) {
                FluidPhysicsCommon.LOGGER.info("Turned {} particles in {} into {} blocks before saving ({} left over)",
                        particles, level.dimension(), blocks, particles - blocks * ParticleSoA.PARTICLES_PER_BLOCK);
            }
        }
    }

    /** Drops all simulations and stops the solver threads, so the next server (another world) starts fresh. */
    public static void onServerStopped() {
        SIMULATIONS.clear();
        INTERACTIONS.clear();
        FLOODGATES.clear();
        FluidNetworking.sent().reset();
        limit = DEFAULT_LIMIT;
        floodgates = false;
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

    public static boolean floodgatesEnabled() {
        return floodgates;
    }

    /**
     * Turns the floodgates (Plan.md §7.3.3) on or off for every level until the server stops. Turning them off forgets
     * the openings that were still waiting.
     */
    public static void setFloodgates(boolean enabled) {
        floodgates = enabled;
        if (!enabled) {
            FLOODGATES.clear();
        }
    }

    /** Openings in {@code level} whose sources still wait to be converted. */
    public static int floodgatesPending(ServerLevel level) {
        ServerFloodgates gates = FLOODGATES.get(level);
        return gates == null ? 0 : gates.pending();
    }

    /**
     * Called before every block change in a server level while the floodgates are enabled: remembers the block if it
     * stops holding vanilla water or lava back.
     */
    public static void beforeBlockChange(ServerLevel level, BlockPos pos, BlockState next) {
        if (!floodgates) {
            return;
        }
        ServerFloodgates gates = FLOODGATES.get(level);
        if (gates == null) {
            gates = new ServerFloodgates(level);
            FLOODGATES.put(level, gates);
        }
        gates.beforeChange(pos, next);
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
     * Removes the particles of {@code level} picked with {@link FluidSimulation#mark} and friends, and tells the clients
     * that see them: one condense event per block and material, so each client removes the same number of particles
     * from the same blocks.
     *
     * @return the number of particles removed
     */
    public static int removeMarked(ServerLevel level, FluidSimulation simulation) {
        int removed = simulation.removeMarked(REMOVAL_SINK);
        for (byte m = 0; m < ParticleSoA.MATERIAL_COUNT; m++) {
            LongArrayList blocks = REMOVED_BLOCKS[m];
            for (int b = 0; b < blocks.size(); b++) {
                long key = blocks.getLong(b);
                int count = REMOVED[m].get(key);
                while (count > 0) {
                    int part = Math.min(count, Short.MAX_VALUE);
                    FluidNetworking.sendToTracking(level, BlockPos.getX(key), BlockPos.getZ(key),
                            new FluidCondensePayload(key, m, (short) part));
                    count -= part;
                }
            }
            blocks.clear();
            REMOVED[m].clear();
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
        ServerInteractions interactions = INTERACTIONS.get(level);
        if (interactions != null) {
            interactions.clear();
        }
        ServerFloodgates gates = FLOODGATES.get(level);
        if (gates != null) {
            gates.clear();
        }
        // Sent even if the server had none: clients may hold particles that drifted apart from the server's.
        FluidNetworking.sendToLevel(level, FluidClearPayload.INSTANCE);
        return removed;
    }
}
