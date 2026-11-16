// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.server;

import dev.tore.fluidphysics.FluidPhysicsCommon;
import dev.tore.fluidphysics.interaction.Condensation;
import dev.tore.fluidphysics.interaction.ForcedCondensation;
import dev.tore.fluidphysics.physics.ChunkKeys;
import dev.tore.fluidphysics.physics.FluidSimulation;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.function.LongConsumer;
import net.minecraft.server.level.ServerLevel;

/**
 * Keeps the particles of one server level inside its simulated area: chunks that are loaded and in block-ticking
 * range (near a player, or force-loaded). Plan.md §7.5.
 *
 * <p>After each tick:
 * <ul>
 *   <li>every simulated chunk that holds particles gets a {@link ParticleTickets ticket}, which keeps it loaded (not
 *       ticking) as long as particles are in it;</li>
 *   <li>the particles of a chunk that is still loaded but no longer ticking (the players went away) become vanilla
 *       blocks ({@link ForcedCondensation});</li>
 *   <li>particles in a chunk that is not loaded at all are removed (the tickets should prevent that);</li>
 *   <li>tickets of chunks without particles are released, once a second so particles that cross a chunk border back
 *       and forth do not add and remove a ticket every tick.</li>
 * </ul>
 *
 * <p>Why tickets: the chunk unload events of the loaders fire after the chunk was saved (Fabric always, NeoForge since
 * 1.21.11 as well), so blocks placed then are lost. And between two ticks a chunk can leave both the simulation and
 * the loading distance of a player; it then unloads early in the next level tick, before any hook after the level
 * tick runs. The ticket keeps such a chunk loaded until the guard has turned its particles into blocks with ordinary
 * {@code setBlock} calls.
 *
 * <p>Server thread only. Allocates nothing per tick unless chunks gain or lose tickets.
 */
final class ParticleChunkGuard {
    /** Tickets of chunks without particles are released every this many ticks. */
    static final int RELEASE_INTERVAL = 20;

    private final ServerLevel level;
    private final ForcedCondensation forced = new ForcedCondensation();
    /** Chunks that hold particles this tick. */
    private final LongOpenHashSet occupied = new LongOpenHashSet();
    /** Chunks that hold one of our tickets. */
    private final LongOpenHashSet ticketed = new LongOpenHashSet();
    /** Occupied chunks that are loaded but not ticking; their particles become blocks. */
    private final LongOpenHashSet outside = new LongOpenHashSet();
    /** Occupied chunks that are not loaded; their particles are removed. */
    private final LongOpenHashSet unloaded = new LongOpenHashSet();
    private final LongArrayList toRelease = new LongArrayList();
    private final LongConsumer classifyTicking = this::classifyTicking;
    private final LongConsumer classifyLoaded = this::classifyLoaded;
    private final LongConsumer collectUnused = this::collectUnused;
    private final LongConsumer releaseTicket = this::removeTicket;
    private long ticks;

    ParticleChunkGuard(ServerLevel level) {
        this.level = level;
    }

    /** Checks the chunks of all particles; call after every tick of the level, also while it is frozen. */
    void tick(FluidSimulation simulation, Condensation.World world) {
        ticks++;
        occupied.clear();
        outside.clear();
        unloaded.clear();
        simulation.collectChunks(occupied);
        occupied.forEach(classifyTicking);
        boolean changed = removeOutside(simulation, world);
        if (changed || ticks % RELEASE_INTERVAL == 0) {
            releaseUnused();
        }
    }

    /**
     * Turns every particle of the level into vanilla blocks, ticking or not, and releases all tickets. For a stopping
     * server, before the worlds are saved.
     *
     * @return the number of vanilla blocks placed
     */
    int condenseAll(FluidSimulation simulation, Condensation.World world) {
        occupied.clear();
        outside.clear();
        unloaded.clear();
        simulation.collectChunks(occupied);
        occupied.forEach(classifyLoaded);
        boolean any = removeOutside(simulation, world);
        releaseAll();
        return any ? forced.lastPlaced() : 0;
    }

    /** Releases every ticket (after {@code /fluidphysics clear} and when the server stops). */
    void releaseAll() {
        ticketed.forEach(releaseTicket);
        ticketed.clear();
    }

    /** Chunks holding a ticket right now. */
    int ticketCount() {
        return ticketed.size();
    }

    /** Sorts an occupied chunk into {@link #unloaded}, {@link #outside} or the simulated chunks, which get a ticket. */
    private void classifyTicking(long chunk) {
        int x = ChunkKeys.x(chunk);
        int z = ChunkKeys.z(chunk);
        if (level.getChunkSource().getChunkNow(x, z) == null) {
            unloaded.add(chunk);
        } else if (!level.shouldTickBlocksAt(chunk)) {
            outside.add(chunk);
        } else if (ticketed.add(chunk)) {
            ParticleTickets.add(level, x, z);
        }
    }

    /** Sorts an occupied chunk into {@link #unloaded} or (loaded, whether ticking or not) {@link #outside}. */
    private void classifyLoaded(long chunk) {
        if (level.getChunkSource().getChunkNow(ChunkKeys.x(chunk), ChunkKeys.z(chunk)) == null) {
            unloaded.add(chunk);
        } else {
            outside.add(chunk);
        }
    }

    /**
     * Condenses the particles in {@link #outside} chunks and removes those in {@link #unloaded} ones, telling the
     * clients.
     *
     * @return whether any particle was removed
     */
    private boolean removeOutside(FluidSimulation simulation, Condensation.World world) {
        if (outside.isEmpty() && unloaded.isEmpty()) {
            return false;
        }
        int lost = simulation.markInChunks(unloaded);
        forced.run(simulation, outside, world);
        simulation.countLeftSimulation(forced.lastCondensed(), forced.lastRemoved() + lost);
        ServerSimulations.removeMarked(level, simulation);
        if (lost > 0) {
            FluidPhysicsCommon.LOGGER.debug("Removed {} particles in {} unloaded chunks of {}", lost, unloaded.size(),
                    level);
        }
        return true;
    }

    /** Releases the tickets of chunks that hold no particles any more. */
    private void releaseUnused() {
        toRelease.clear();
        ticketed.forEach(collectUnused);
        for (int k = 0; k < toRelease.size(); k++) {
            long chunk = toRelease.getLong(k);
            ticketed.remove(chunk);
            removeTicket(chunk);
        }
    }

    private void removeTicket(long chunk) {
        ParticleTickets.remove(level, ChunkKeys.x(chunk), ChunkKeys.z(chunk));
    }

    /** Collects a ticketed chunk for release if no particle is left in it. */
    private void collectUnused(long chunk) {
        if (!occupied.contains(chunk) || outside.contains(chunk) || unloaded.contains(chunk)) {
            toRelease.add(chunk);
        }
    }
}
