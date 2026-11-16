// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.network;

import java.util.Arrays;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Counts the fluid physics packets the server sends, per payload kind and per wall-clock second, for
 * {@code /fluidphysics stats} (Plan.md §6.5) and as the baseline for batching and rate limits (Step 6d).
 *
 * <p>Sizes are estimates of the bytes on the wire for one packet to one player: the payload data (fixed per kind,
 * checked by {@code PayloadCodecTest}) plus a custom-payload header of 3 bytes and the payload id string (frame
 * length, packet id and string length, one byte each at these sizes). Compression and encryption are not counted;
 * packets this small are below vanilla's compression threshold.
 *
 * <p>Counting allocates nothing. Not thread-safe: count and read on the server thread.
 */
public final class NetworkStats {
    public static final int KIND_SPAWN = 0;
    public static final int KIND_IMPULSE = 1;
    public static final int KIND_CONDENSE = 2;
    public static final int KIND_CLEAR = 3;
    public static final int KIND_COUNT = 4;
    /** Full seconds covered by the per-second figures. */
    public static final int WINDOW_SECONDS = 10;

    private static final String[] KIND_NAMES = {"spawn", "impulse", "condense", "clear"};
    /** Encoded payload size per kind; must match the stream codecs. */
    private static final int[] DATA_BYTES = {23, 20, 11, 0};
    private static final int HEADER_BYTES = 3;
    private static final int[] PACKET_BYTES = {
            packetBytes(FluidSpawnPayload.TYPE, DATA_BYTES[KIND_SPAWN]),
            packetBytes(FluidImpulsePayload.TYPE, DATA_BYTES[KIND_IMPULSE]),
            packetBytes(FluidCondensePayload.TYPE, DATA_BYTES[KIND_CONDENSE]),
            packetBytes(FluidClearPayload.TYPE, DATA_BYTES[KIND_CLEAR])};
    /** The full window plus the second in progress, which the per-second figures leave out. */
    private static final int SLOTS = WINDOW_SECONDS + 1;

    /** Packets per slot and kind: slot {@code second % SLOTS}. */
    private final long[] packets = new long[SLOTS * KIND_COUNT];
    private final long[] bytes = new long[SLOTS];
    private final long[] totalPackets = new long[KIND_COUNT];
    private long totalBytes;
    /** The second in progress; {@code Long.MIN_VALUE} before the first packet. */
    private long currentSecond = Long.MIN_VALUE;
    private long firstSecond;

    private static int packetBytes(CustomPacketPayload.Type<?> type, int dataBytes) {
        return HEADER_BYTES + type.id().toString().length() + dataBytes;
    }

    /** Short English name of a kind ({@code KIND_*}). */
    public static String kindName(int kind) {
        return KIND_NAMES[kind];
    }

    /** Encoded payload data of one packet of {@code kind}. */
    public static int dataBytes(int kind) {
        return DATA_BYTES[kind];
    }

    /** Estimated bytes on the wire of one packet of {@code kind}, see the class description. */
    public static int packetBytes(int kind) {
        return PACKET_BYTES[kind];
    }

    /** The kind ({@code KIND_*}) of one of our payloads, or -1 for any other payload. */
    public static int kindOf(CustomPacketPayload payload) {
        if (payload instanceof FluidSpawnPayload) {
            return KIND_SPAWN;
        }
        if (payload instanceof FluidImpulsePayload) {
            return KIND_IMPULSE;
        }
        if (payload instanceof FluidCondensePayload) {
            return KIND_CONDENSE;
        }
        return payload instanceof FluidClearPayload ? KIND_CLEAR : -1;
    }

    /** Counts one packet of {@code payload} sent to one player. */
    public void count(CustomPacketPayload payload) {
        int kind = kindOf(payload);
        if (kind >= 0) {
            count(kind, nowSecond());
        }
    }

    void count(int kind, long second) {
        if (currentSecond == Long.MIN_VALUE) {
            currentSecond = second;
            firstSecond = second;
        } else {
            roll(second);
        }
        int slot = slot(second);
        packets[slot * KIND_COUNT + kind]++;
        bytes[slot] += PACKET_BYTES[kind];
        totalPackets[kind]++;
        totalBytes += PACKET_BYTES[kind];
    }

    /** Mean packets of {@code kind} per second over the last {@link #WINDOW_SECONDS} full seconds. */
    public double packetsPerSecond(int kind) {
        return packetsPerSecond(kind, nowSecond());
    }

    double packetsPerSecond(int kind, long second) {
        roll(second);
        long sum = 0L;
        for (int s = 1; s <= WINDOW_SECONDS; s++) {
            sum += packets[slot(second - s) * KIND_COUNT + kind];
        }
        return sum / (double) windowSeconds(second);
    }

    /** Mean estimated bytes per second over the last {@link #WINDOW_SECONDS} full seconds, all kinds. */
    public double bytesPerSecond() {
        return bytesPerSecond(nowSecond());
    }

    double bytesPerSecond(long second) {
        roll(second);
        long sum = 0L;
        for (int s = 1; s <= WINDOW_SECONDS; s++) {
            sum += bytes[slot(second - s)];
        }
        return sum / (double) windowSeconds(second);
    }

    /** Most packets (all kinds) in one of the last {@link #WINDOW_SECONDS} full seconds. */
    public long peakPacketsPerSecond() {
        return peakPacketsPerSecond(nowSecond());
    }

    long peakPacketsPerSecond(long second) {
        roll(second);
        long peak = 0L;
        for (int s = 1; s <= WINDOW_SECONDS; s++) {
            int slot = slot(second - s);
            long sum = 0L;
            for (int k = 0; k < KIND_COUNT; k++) {
                sum += packets[slot * KIND_COUNT + k];
            }
            peak = Math.max(peak, sum);
        }
        return peak;
    }

    /** Packets of {@code kind} since the server started. */
    public long totalPackets(int kind) {
        return totalPackets[kind];
    }

    /** Estimated bytes since the server started, all kinds. */
    public long totalBytes() {
        return totalBytes;
    }

    /** Forgets everything (the server stopped). */
    public void reset() {
        Arrays.fill(packets, 0L);
        Arrays.fill(bytes, 0L);
        Arrays.fill(totalPackets, 0L);
        totalBytes = 0L;
        currentSecond = Long.MIN_VALUE;
    }

    /** Moves the second in progress to {@code second}, clearing the slots of the seconds in between. */
    private void roll(long second) {
        if (currentSecond == Long.MIN_VALUE || second <= currentSecond) {
            return;
        }
        long steps = Math.min(second - currentSecond, SLOTS);
        for (long s = 1; s <= steps; s++) {
            int slot = slot(currentSecond + s);
            bytes[slot] = 0L;
            for (int k = 0; k < KIND_COUNT; k++) {
                packets[slot * KIND_COUNT + k] = 0L;
            }
        }
        currentSecond = second;
    }

    /** Full seconds since the first packet, at most {@link #WINDOW_SECONDS} and at least 1. */
    private long windowSeconds(long second) {
        if (currentSecond == Long.MIN_VALUE) {
            return WINDOW_SECONDS;
        }
        return Math.max(1L, Math.min(WINDOW_SECONDS, second - firstSecond));
    }

    private static int slot(long second) {
        return (int) Math.floorMod(second, (long) SLOTS);
    }

    private static long nowSecond() {
        return System.nanoTime() / 1_000_000_000L;
    }
}
