package com.ysmef.geomodel.network;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlmPackChunkCodecTest {
    @Test
    void nonzeroSliceAndHeartbeatMatchTheExistingWireFormat() {
        byte[] shared = new byte[256];
        for (int i = 0; i < shared.length; i++) shared[i] = (byte) i;
        var slice = new TlmPackNetwork.Chunk("maid:alice", 41, 256, shared, 41, 131);
        assertSame(shared, slice.data());
        assertCompatible(slice, Arrays.copyOfRange(shared, 41, 172));
        assertCompatible(new TlmPackNetwork.Chunk("maid:alice", 0, 256, new byte[0]), new byte[0]);
    }

    private static void assertCompatible(TlmPackNetwork.Chunk message, byte[] expectedData) {
        var old = new FriendlyByteBuf(Unpooled.buffer());
        var updated = new FriendlyByteBuf(Unpooled.buffer());
        try {
            old.writeUtf(message.modelId());
            old.writeLong(message.offset());
            old.writeLong(message.total());
            old.writeByteArray(expectedData);
            message.encode(updated);
            assertTrue(ByteBufUtil.equals(old, updated));

            var decoded = TlmPackNetwork.Chunk.decode(old);
            assertEquals(message.modelId(), decoded.modelId());
            assertEquals(message.offset(), decoded.offset());
            assertEquals(message.total(), decoded.total());
            assertEquals(0, decoded.dataOffset());
            assertEquals(expectedData.length, decoded.dataLength());
            assertArrayEquals(expectedData, decoded.data());
        } finally {
            updated.release();
            old.release();
        }
    }
}
