// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.server;

import dev.tore.fluidphysics.FluidPhysicsCommon;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * The chunk ticket that keeps a chunk with particles loaded (Plan.md §7.5). It loads the chunk fully but does not make
 * it tick: ticket level 33, like the border around the chunks a player keeps loaded.
 *
 * <p>1.21.1 creates ticket types freely ({@code TicketType<T>}, tickets keyed by a value); 1.21.11 has a registry of
 * ticket types (a record with flags), so the loaders register {@link #TYPE} through
 * {@code ModRegistry.registerTicketTypes} there.
 */
public final class ParticleTickets {
    public static final Identifier ID = FluidPhysicsCommon.id("particles");

    //? if >=1.21.11 {
    /** Loads the chunk, no timeout, not saved with the level, does not simulate. */
    public static final TicketType TYPE = new TicketType(0L, TicketType.FLAG_LOADING);
    //?} else {
    /*public static final TicketType<ChunkPos> TYPE =
            TicketType.create(ID.toString(), java.util.Comparator.comparingLong(ChunkPos::toLong));
    *///?}

    private ParticleTickets() {
    }

    /** Adds the ticket to chunk ({@code x}, {@code z}); adding it twice is harmless. */
    public static void add(ServerLevel level, int x, int z) {
        ChunkPos pos = new ChunkPos(x, z);
        //? if >=1.21.11 {
        level.getChunkSource().addTicketWithRadius(TYPE, pos, 0);
        //?} else {
        /*level.getChunkSource().addRegionTicket(TYPE, pos, 0, pos);
        *///?}
    }

    /** Removes the ticket from chunk ({@code x}, {@code z}). */
    public static void remove(ServerLevel level, int x, int z) {
        ChunkPos pos = new ChunkPos(x, z);
        //? if >=1.21.11 {
        level.getChunkSource().removeTicketWithRadius(TYPE, pos, 0);
        //?} else {
        /*level.getChunkSource().removeRegionTicket(TYPE, pos, 0, pos);
        *///?}
    }
}
