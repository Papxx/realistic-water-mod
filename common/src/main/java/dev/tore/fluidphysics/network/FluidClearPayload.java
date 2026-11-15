// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.network;

import dev.tore.fluidphysics.FluidPhysicsCommon;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: all particles of the player's current level were removed ({@code /fluidphysics clear}). Carries no
 * data; the clients clear their own simulations.
 */
public record FluidClearPayload() implements CustomPacketPayload {
    public static final FluidClearPayload INSTANCE = new FluidClearPayload();

    public static final CustomPacketPayload.Type<FluidClearPayload> TYPE =
            new CustomPacketPayload.Type<>(FluidPhysicsCommon.id("fluid_clear"));

    public static final StreamCodec<ByteBuf, FluidClearPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<FluidClearPayload> type() {
        return TYPE;
    }
}
