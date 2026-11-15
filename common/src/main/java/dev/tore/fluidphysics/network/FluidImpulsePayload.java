// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.network;

import dev.tore.fluidphysics.FluidPhysicsCommon;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: push the particles around a point outward (TNT, a falling anvil, a piston, a player jumping in).
 * Each client applies the same impulse to its own particles. 20 bytes.
 *
 * @param x      centre in world coordinates (likewise {@code y}, {@code z})
 * @param radius blocks around the centre that are affected
 * @param force  velocity change at the centre in blocks per second, falling off to 0 at the radius; negative pulls in
 */
public record FluidImpulsePayload(float x, float y, float z, float radius, float force) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<FluidImpulsePayload> TYPE =
            new CustomPacketPayload.Type<>(FluidPhysicsCommon.id("fluid_impulse"));

    public static final StreamCodec<ByteBuf, FluidImpulsePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, FluidImpulsePayload::x,
            ByteBufCodecs.FLOAT, FluidImpulsePayload::y,
            ByteBufCodecs.FLOAT, FluidImpulsePayload::z,
            ByteBufCodecs.FLOAT, FluidImpulsePayload::radius,
            ByteBufCodecs.FLOAT, FluidImpulsePayload::force,
            FluidImpulsePayload::new);

    @Override
    public CustomPacketPayload.Type<FluidImpulsePayload> type() {
        return TYPE;
    }
}
