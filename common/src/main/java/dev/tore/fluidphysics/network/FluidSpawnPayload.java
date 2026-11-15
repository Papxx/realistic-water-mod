// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.network;

import dev.tore.fluidphysics.FluidPhysicsCommon;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: particles appeared on the 4 x 4 x 4 micro-grid of one block (a bucket was emptied, a block crumbled,
 * a command ran). Each client spawns the same particles into its own simulation. 23 bytes.
 *
 * @param packedBlockPos the block, packed with {@code BlockPos.asLong(x, y, z)}
 * @param materialType   material id ({@code ParticleSoA.MATERIAL_*}); clients ignore unknown ids
 * @param count          number of particles, 1 to 64
 * @param dirX           initial velocity along x in blocks per second (likewise {@code dirY}, {@code dirZ})
 */
public record FluidSpawnPayload(long packedBlockPos, byte materialType, short count, float dirX, float dirY, float dirZ)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<FluidSpawnPayload> TYPE =
            new CustomPacketPayload.Type<>(FluidPhysicsCommon.id("fluid_spawn"));

    public static final StreamCodec<ByteBuf, FluidSpawnPayload> STREAM_CODEC = StreamCodec.composite(
            FluidStreamCodecs.PACKED_POS, FluidSpawnPayload::packedBlockPos,
            ByteBufCodecs.BYTE, FluidSpawnPayload::materialType,
            ByteBufCodecs.SHORT, FluidSpawnPayload::count,
            ByteBufCodecs.FLOAT, FluidSpawnPayload::dirX,
            ByteBufCodecs.FLOAT, FluidSpawnPayload::dirY,
            ByteBufCodecs.FLOAT, FluidSpawnPayload::dirZ,
            FluidSpawnPayload::new);

    @Override
    public CustomPacketPayload.Type<FluidSpawnPayload> type() {
        return TYPE;
    }
}
