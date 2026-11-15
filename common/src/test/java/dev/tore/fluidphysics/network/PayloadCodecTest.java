// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

class PayloadCodecTest {
    @Test
    void spawnPayloadSurvivesTheWire() {
        // Negative x and z: packed positions with the highest bits set.
        FluidSpawnPayload payload = new FluidSpawnPayload(BlockPos.asLong(-30_000_000, -64, -123), (byte) 3,
                (short) 64, 1.5f, -2.25f, 0f);
        assertEquals(23, roundTrip(FluidSpawnPayload.STREAM_CODEC, payload));
    }

    @Test
    void impulsePayloadSurvivesTheWire() {
        assertEquals(20, roundTrip(FluidImpulsePayload.STREAM_CODEC,
                new FluidImpulsePayload(12.5f, 70f, -3.75f, 4f, -8f)));
    }

    @Test
    void condensePayloadSurvivesTheWire() {
        assertEquals(11, roundTrip(FluidCondensePayload.STREAM_CODEC,
                new FluidCondensePayload(BlockPos.asLong(29_999_999, 319, 29_999_999), (byte) 1, (short) 64)));
    }

    @Test
    void clearPayloadHasNoData() {
        assertEquals(0, roundTrip(FluidClearPayload.STREAM_CODEC, FluidClearPayload.INSTANCE));
    }

    @Test
    void payloadTypesAreDistinctAndNamespaced() {
        List<String> ids = List.of(FluidSpawnPayload.TYPE.id().toString(), FluidImpulsePayload.TYPE.id().toString(),
                FluidCondensePayload.TYPE.id().toString(), FluidClearPayload.TYPE.id().toString());
        assertEquals(List.of("fluidphysics:fluid_spawn", "fluidphysics:fluid_impulse", "fluidphysics:fluid_condense",
                "fluidphysics:fluid_clear"), ids);
        assertNotEquals(FluidSpawnPayload.TYPE, FluidCondensePayload.TYPE);
    }

    /** Encodes and decodes {@code payload}, checks the result is equal and fully read, and returns the encoded size. */
    private static <T> int roundTrip(StreamCodec<ByteBuf, T> codec, T payload) {
        ByteBuf buf = Unpooled.buffer();
        try {
            codec.encode(buf, payload);
            int size = buf.readableBytes();
            T decoded = codec.decode(buf);
            assertEquals(payload, decoded);
            assertEquals(0, buf.readableBytes(), "decoder left bytes unread");
            return size;
        } finally {
            buf.release();
        }
    }
}
