package dev.vegui.vnp.velocity;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacketObservationTest {
    @Test void measuresZeroBasedEventBufferIncludingActualPacketIdWithoutChangingIt() {
        var buffer = Unpooled.buffer().writeBytes(new byte[]{(byte) 0x81, 1, 5, 6, 7});
        try {
            buffer.readerIndex(2); int references = buffer.refCnt();
            assertEquals(5, PacketEventsCaptureProvider.observedBytes(buffer));
            assertEquals(2, buffer.readerIndex()); assertEquals(5, buffer.writerIndex()); assertEquals(references, buffer.refCnt());
            buffer.readerIndex(0); assertEquals(-1, PacketEventsCaptureProvider.observedBytes(buffer));
        } finally { buffer.release(); }
    }
}
