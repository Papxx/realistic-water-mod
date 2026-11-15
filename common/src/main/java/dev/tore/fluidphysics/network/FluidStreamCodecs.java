// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Stream codecs shared by the payloads. */
final class FluidStreamCodecs {
    /**
     * A {@code BlockPos.asLong} value as 8 fixed bytes, like vanilla's {@code BlockPos.STREAM_CODEC}.
     * {@code ByteBufCodecs.LONG} does not exist in 1.21.1, and {@code VAR_LONG} would need up to 10 bytes because packed
     * positions keep x in the highest bits.
     */
    static final StreamCodec<ByteBuf, Long> PACKED_POS = StreamCodec.of((buf, value) -> buf.writeLong(value), ByteBuf::readLong);

    private FluidStreamCodecs() {
    }
}
