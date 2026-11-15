// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.network;

import dev.tore.fluidphysics.FluidPhysicsCommon;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: particles in one block turned back into a vanilla block or filled a cauldron. Each client removes
 * the same number of particles of that material from the block (and, if it holds fewer, from the blocks around it).
 * 11 bytes.
 *
 * @param packedBlockPos the block, packed with {@code BlockPos.asLong(x, y, z)}
 * @param materialType   material id ({@code ParticleSoA.MATERIAL_*})
 * @param removedCount   number of particles removed
 */
public record FluidCondensePayload(long packedBlockPos, byte materialType, short removedCount)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<FluidCondensePayload> TYPE =
            new CustomPacketPayload.Type<>(FluidPhysicsCommon.id("fluid_condense"));

    public static final StreamCodec<ByteBuf, FluidCondensePayload> STREAM_CODEC = StreamCodec.composite(
            FluidStreamCodecs.PACKED_POS, FluidCondensePayload::packedBlockPos,
            ByteBufCodecs.BYTE, FluidCondensePayload::materialType,
            ByteBufCodecs.SHORT, FluidCondensePayload::removedCount,
            FluidCondensePayload::new);

    @Override
    public CustomPacketPayload.Type<FluidCondensePayload> type() {
        return TYPE;
    }
}
