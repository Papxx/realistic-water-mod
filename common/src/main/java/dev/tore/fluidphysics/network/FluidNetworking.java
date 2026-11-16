// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.network;

import dev.tore.fluidphysics.platform.Services;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * Picks the players a payload goes to. Particle positions are never sent: only events, to the players who can see
 * where they happen.
 */
public final class FluidNetworking {
    /** What the server sent, for {@code /fluidphysics stats}. */
    private static final NetworkStats SENT = new NetworkStats();

    private FluidNetworking() {
    }

    /** Packets the server sent since it started (server thread only); reset when it stops. */
    public static NetworkStats sent() {
        return SENT;
    }

    /** Sends {@code payload} to every player who has the chunk of block column ({@code blockX}, {@code blockZ}) loaded. */
    public static void sendToTracking(ServerLevel level, int blockX, int blockZ, CustomPacketPayload payload) {
        ChunkPos chunk = new ChunkPos(SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ));
        // The same lookup Fabric's PlayerLookup.tracking and NeoForge's PacketDistributor.sendToPlayersTrackingChunk use.
        for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(chunk, false)) {
            Services.PLATFORM.sendToPlayer(player, payload);
            SENT.count(payload);
        }
    }

    /** Sends {@code payload} to every player in {@code level}. */
    public static void sendToLevel(ServerLevel level, CustomPacketPayload payload) {
        for (ServerPlayer player : level.players()) {
            Services.PLATFORM.sendToPlayer(player, payload);
            SENT.count(payload);
        }
    }
}
