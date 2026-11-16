// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

class NetworkStatsTest {
    @Test
    void dataSizesMatchTheStreamCodecs() {
        assertEquals(NetworkStats.dataBytes(NetworkStats.KIND_SPAWN), encodedSize(FluidSpawnPayload.STREAM_CODEC,
                new FluidSpawnPayload(BlockPos.asLong(1, 2, 3), (byte) 0, (short) 64, 0f, 0f, 0f)));
        assertEquals(NetworkStats.dataBytes(NetworkStats.KIND_IMPULSE), encodedSize(FluidImpulsePayload.STREAM_CODEC,
                new FluidImpulsePayload(0f, 0f, 0f, 1f, 1f)));
        assertEquals(NetworkStats.dataBytes(NetworkStats.KIND_CONDENSE), encodedSize(
                FluidCondensePayload.STREAM_CODEC, new FluidCondensePayload(0L, (byte) 0, (short) 1)));
        assertEquals(NetworkStats.dataBytes(NetworkStats.KIND_CLEAR),
                encodedSize(FluidClearPayload.STREAM_CODEC, FluidClearPayload.INSTANCE));
        // Header: frame length, packet id and string length (1 byte each) plus "fluidphysics:fluid_spawn".
        assertEquals(3 + 24 + 23, NetworkStats.packetBytes(NetworkStats.KIND_SPAWN));
    }

    @Test
    void kindsOfPayloads() {
        assertEquals(NetworkStats.KIND_IMPULSE, NetworkStats.kindOf(new FluidImpulsePayload(0f, 0f, 0f, 1f, 1f)));
        assertEquals(NetworkStats.KIND_CLEAR, NetworkStats.kindOf(FluidClearPayload.INSTANCE));
    }

    @Test
    void ratesCoverTheLastFullSeconds() {
        NetworkStats stats = new NetworkStats();
        long t = 1_000L;
        // Second t: 3 spawns; second t + 1: 1 condense; the second in progress (t + 2) is left out.
        for (int i = 0; i < 3; i++) {
            stats.count(NetworkStats.KIND_SPAWN, t);
        }
        stats.count(NetworkStats.KIND_CONDENSE, t + 1);
        stats.count(NetworkStats.KIND_SPAWN, t + 2);

        // Two full seconds since the first packet.
        assertEquals(1.5, stats.packetsPerSecond(NetworkStats.KIND_SPAWN, t + 2), 1e-9);
        assertEquals(0.5, stats.packetsPerSecond(NetworkStats.KIND_CONDENSE, t + 2), 1e-9);
        assertEquals(3, stats.peakPacketsPerSecond(t + 2));
        double bytes = 3 * NetworkStats.packetBytes(NetworkStats.KIND_SPAWN)
                + NetworkStats.packetBytes(NetworkStats.KIND_CONDENSE);
        assertEquals(bytes / 2, stats.bytesPerSecond(t + 2), 1e-9);
        assertEquals(4, stats.totalPackets(NetworkStats.KIND_SPAWN));

        // Much later the window is empty again, but the totals stay.
        assertEquals(0.0, stats.packetsPerSecond(NetworkStats.KIND_SPAWN, t + 100), 1e-9);
        assertEquals(0, stats.peakPacketsPerSecond(t + 100));
        assertEquals(4, stats.totalPackets(NetworkStats.KIND_SPAWN));

        stats.reset();
        assertEquals(0, stats.totalPackets(NetworkStats.KIND_SPAWN));
        assertEquals(0L, stats.totalBytes());
    }

    @Test
    void anOldSecondDoesNotLeakIntoTheWindowAfterItWrapsAround() {
        NetworkStats stats = new NetworkStats();
        stats.count(NetworkStats.KIND_IMPULSE, 0L);
        // 11 seconds later the ring slot of second 0 is reused for second 11.
        stats.count(NetworkStats.KIND_IMPULSE, 11L);
        assertEquals(0.0, stats.packetsPerSecond(NetworkStats.KIND_IMPULSE, 11L), 1e-9);
        assertEquals(0.1, stats.packetsPerSecond(NetworkStats.KIND_IMPULSE, 12L), 1e-9);
    }

    private static <T> int encodedSize(StreamCodec<ByteBuf, T> codec, T payload) {
        ByteBuf buf = Unpooled.buffer();
        try {
            codec.encode(buf, payload);
            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }
}
