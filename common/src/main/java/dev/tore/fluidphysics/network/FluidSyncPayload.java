// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.network;

import dev.tore.fluidphysics.FluidPhysicsCommon;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: how many particles of each material the server holds in each block of one chunk (the coarse-grid
 * resync of Plan.md §6.3.3; never particle positions). A client that joined late or came closer has no particles there
 * and rebuilds them from the counts; a client that is close enough ignores it.
 *
 * @param chunkX    chunk column of the snapshot
 * @param chunkZ    chunk column of the snapshot
 * @param full      whether the client must replace its particles in the chunk even if its count is close (first
 *                  snapshot of a chunk, or the chunk emptied); otherwise it only does so when it has drifted apart
 * @param blocks    packed block positions ({@code BlockPos.asLong}), one entry per block and material
 * @param materials material id of each entry
 * @param counts    particles of that material in that block, 1 to 64
 */
public record FluidSyncPayload(int chunkX, int chunkZ, boolean full, long[] blocks, byte[] materials, short[] counts)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<FluidSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(FluidPhysicsCommon.id("fluid_sync"));
    /** Most entries one snapshot may carry (a chunk holds at most 32,768 particles, so at most that many entries). */
    public static final int MAX_ENTRIES = 32768;

    public static final StreamCodec<ByteBuf, FluidSyncPayload> STREAM_CODEC = StreamCodec.of(
            FluidSyncPayload::write, FluidSyncPayload::read);

    private static void write(ByteBuf buf, FluidSyncPayload p) {
        buf.writeInt(p.chunkX);
        buf.writeInt(p.chunkZ);
        buf.writeBoolean(p.full);
        buf.writeShort(p.blocks.length);
        for (int i = 0; i < p.blocks.length; i++) {
            buf.writeLong(p.blocks[i]);
            buf.writeByte(p.materials[i]);
            buf.writeShort(p.counts[i]);
        }
    }

    private static FluidSyncPayload read(ByteBuf buf) {
        int chunkX = buf.readInt();
        int chunkZ = buf.readInt();
        boolean full = buf.readBoolean();
        int n = buf.readUnsignedShort();
        if (n > MAX_ENTRIES || buf.readableBytes() < n * 11L) {
            throw new IllegalArgumentException("Bad fluid sync payload: " + n + " entries");
        }
        long[] blocks = new long[n];
        byte[] materials = new byte[n];
        short[] counts = new short[n];
        for (int i = 0; i < n; i++) {
            blocks[i] = buf.readLong();
            materials[i] = buf.readByte();
            counts[i] = buf.readShort();
        }
        return new FluidSyncPayload(chunkX, chunkZ, full, blocks, materials, counts);
    }

    /** Particles in the snapshot. */
    public int total() {
        int sum = 0;
        for (short c : counts) {
            sum += c;
        }
        return sum;
    }

    @Override
    public CustomPacketPayload.Type<FluidSyncPayload> type() {
        return TYPE;
    }
}
