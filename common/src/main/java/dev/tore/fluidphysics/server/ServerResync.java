// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.server;

import dev.tore.fluidphysics.network.FluidSyncPayload;
import dev.tore.fluidphysics.physics.ChunkKeys;
import dev.tore.fluidphysics.physics.FluidSimulation;
import dev.tore.fluidphysics.physics.ParticleSoA;
import dev.tore.fluidphysics.platform.Services;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * Coarse-grid resync of one level (Plan.md §6.3.3). Events (spawn, impulse, condense) only reach players who are near
 * when they happen, so a player who joins later or walks closer would never see the particles that already exist. Once
 * a second this class sends each player who has a particle chunk loaded a snapshot of that chunk: particle counts per
 * block and material, never positions. The first snapshot of a chunk makes the client build its particles; later ones
 * (every {@link #REFRESH_SECONDS} seconds) only correct a client that has drifted apart from the server.
 *
 * <p>Runs once per second, not in the solver loop, and allocates freely.
 */
final class ServerResync {
    /** Seconds between the refresh snapshots of chunks a player already has. */
    static final int REFRESH_SECONDS = 5;

    private final ServerLevel level;
    /** Per player: the chunks that were synced and are still occupied and loaded for them. */
    private final Reference2ObjectOpenHashMap<ServerPlayer, LongOpenHashSet> synced = new Reference2ObjectOpenHashMap<>();
    private int seconds;

    ServerResync(ServerLevel level) {
        this.level = level;
    }

    /** One chunk's counts, per material, keyed by packed block position. */
    private static Long2ObjectOpenHashMap<Long2IntOpenHashMap[]> snapshot(FluidSimulation simulation) {
        ParticleSoA p = simulation.particles();
        Long2ObjectOpenHashMap<Long2IntOpenHashMap[]> chunks = new Long2ObjectOpenHashMap<>();
        if (p == null) {
            return chunks;
        }
        for (int i = 0; i < p.activeCount; i++) {
            int bx = p.originX + (int) Math.floor(p.posX[i]);
            int by = p.originY + (int) Math.floor(p.posY[i]);
            int bz = p.originZ + (int) Math.floor(p.posZ[i]);
            long chunk = ChunkKeys.ofBlock(bx, bz);
            Long2IntOpenHashMap[] perMaterial = chunks.get(chunk);
            if (perMaterial == null) {
                perMaterial = new Long2IntOpenHashMap[ParticleSoA.MATERIAL_COUNT];
                chunks.put(chunk, perMaterial);
            }
            byte m = p.material[i];
            if (perMaterial[m] == null) {
                perMaterial[m] = new Long2IntOpenHashMap();
            }
            perMaterial[m].addTo(BlockPos.asLong(bx, by, bz), 1);
        }
        return chunks;
    }

    private static FluidSyncPayload payload(long chunk, boolean full, Long2IntOpenHashMap[] perMaterial) {
        int n = 0;
        if (perMaterial != null) {
            for (Long2IntOpenHashMap map : perMaterial) {
                n += map == null ? 0 : map.size();
            }
        }
        n = Math.min(n, FluidSyncPayload.MAX_ENTRIES);
        long[] blocks = new long[n];
        byte[] materials = new byte[n];
        short[] counts = new short[n];
        int at = 0;
        if (perMaterial != null) {
            for (byte m = 0; m < perMaterial.length && at < n; m++) {
                if (perMaterial[m] == null) {
                    continue;
                }
                for (Long2IntOpenHashMap.Entry e : perMaterial[m].long2IntEntrySet()) {
                    if (at == n) {
                        break;
                    }
                    blocks[at] = e.getLongKey();
                    materials[at] = m;
                    counts[at] = (short) Math.min(e.getIntValue(), ParticleSoA.PARTICLES_PER_BLOCK);
                    at++;
                }
            }
        }
        return new FluidSyncPayload(ChunkKeys.x(chunk), ChunkKeys.z(chunk), full, blocks, materials, counts);
    }

    private List<ServerPlayer> trackers(long chunk) {
        return level.getChunkSource().chunkMap.getPlayers(new ChunkPos(ChunkKeys.x(chunk), ChunkKeys.z(chunk)), false);
    }

    /** Call once per second. */
    void run(FluidSimulation simulation) {
        seconds++;
        boolean refresh = seconds % REFRESH_SECONDS == 0;
        Long2ObjectOpenHashMap<Long2IntOpenHashMap[]> chunks = snapshot(simulation);
        // Forget players who left the level.
        for (Iterator<ServerPlayer> it = synced.keySet().iterator(); it.hasNext(); ) {
            ServerPlayer player = it.next();
            if (player.level() != level || player.hasDisconnected()) {
                it.remove();
            }
        }
        Reference2ObjectOpenHashMap<ServerPlayer, LongOpenHashSet> tracked = new Reference2ObjectOpenHashMap<>();
        for (Long2ObjectOpenHashMap.Entry<Long2IntOpenHashMap[]> entry : chunks.long2ObjectEntrySet()) {
            long chunk = entry.getLongKey();
            FluidSyncPayload full = null;
            FluidSyncPayload refreshed = null;
            for (ServerPlayer player : trackers(chunk)) {
                tracked.computeIfAbsent(player, k -> new LongOpenHashSet()).add(chunk);
                LongOpenHashSet known = synced.computeIfAbsent(player, k -> new LongOpenHashSet());
                if (known.add(chunk)) {
                    full = full != null ? full : payload(chunk, true, entry.getValue());
                    Services.PLATFORM.sendToPlayer(player, full);
                } else if (refresh) {
                    refreshed = refreshed != null ? refreshed : payload(chunk, false, entry.getValue());
                    Services.PLATFORM.sendToPlayer(player, refreshed);
                }
            }
        }
        // Chunks a player had synced that are empty or out of reach now.
        for (Reference2ObjectOpenHashMap.Entry<ServerPlayer, LongOpenHashSet> entry
                : synced.reference2ObjectEntrySet()) {
            LongOpenHashSet stillTracked = tracked.get(entry.getKey());
            LongIterator it = entry.getValue().iterator();
            while (it.hasNext()) {
                long chunk = it.nextLong();
                if (stillTracked != null && stillTracked.contains(chunk)) {
                    continue;
                }
                it.remove();
                if (!chunks.containsKey(chunk) && trackers(chunk).contains(entry.getKey())) {
                    // The particles of this chunk are gone: tell the client, which may still hold some.
                    Services.PLATFORM.sendToPlayer(entry.getKey(), payload(chunk, true, null));
                }
            }
        }
    }

    void clear() {
        synced.clear();
    }
}
