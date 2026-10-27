package dev.vegui.vnp.netty;

import dev.vegui.vnp.core.TrafficProfiler;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrafficCountingHandlerTest {
    private TrafficProfiler profiler() { return new TrafficProfiler(900, true, true); }
    @Test void inboundAndOutboundUseReadableBytesWithoutChangingIndicesOrOwnership() {
        var p = profiler(); var handler = new TrafficCountingHandler(p); var channel = new EmbeddedChannel(handler);
        ByteBuf input = Unpooled.buffer().writeZero(20); input.readerIndex(4);
        channel.writeInbound(input);
        assertEquals(4, input.readerIndex()); assertEquals(20, input.writerIndex()); assertEquals(1, input.refCnt());
        assertSame(input, channel.readInbound()); input.release();
        ByteBuf output = Unpooled.buffer().writeZero(33); channel.writeOutbound(output);
        assertSame(output, channel.readOutbound()); assertEquals(1, output.refCnt()); output.release();
        var sample = p.sample();
        assertEquals("16", sample.traffic().inbound().totalBytes()); assertEquals("33", sample.traffic().outbound().totalBytes());
        assertEquals(1, handler.readEvents()); assertEquals(1, handler.writeEvents());
        channel.finishAndReleaseAll();
    }
    @Test void failedWritesAreNotConfirmedTraffic() {
        var p = profiler(); var channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg); promise.setFailure(new IllegalStateException("transport failure"));
            }
        }, new TrafficCountingHandler(p));
        var promise = channel.newPromise(); channel.pipeline().writeAndFlush(Unpooled.buffer().writeZero(9), promise);
        assertFalse(promise.isSuccess()); assertEquals("0", p.sample().traffic().outbound().totalBytes());
        channel.finishAndReleaseAll();
    }
    @Test void voidPromiseIsMadeObservableAndCountedOnce() {
        var p = profiler(); var channel = new EmbeddedChannel(new TrafficCountingHandler(p));
        channel.pipeline().writeAndFlush(Unpooled.buffer().writeZero(17), channel.voidPromise());
        assertEquals("17", p.sample().traffic().outbound().totalBytes());
        ReferenceCountUtil.release(channel.readOutbound()); channel.finishAndReleaseAll();
    }
    @Test void pendingAndCancelledWritesDoNotCountAndOldSessionRemainsIsolated() {
        var p = profiler(); ChannelPromise[] pending = {null};
        var channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) { ReferenceCountUtil.release(msg); pending[0] = promise; }
        }, new TrafficCountingHandler(p));
        var promise = channel.newPromise(); channel.pipeline().writeAndFlush(Unpooled.buffer().writeZero(13), promise);
        assertEquals("0", p.sample().traffic().outbound().totalBytes());
        p.reset(); pending[0].setSuccess();
        assertEquals("0", p.sample().traffic().outbound().totalBytes());
        promise = channel.newPromise(); channel.pipeline().writeAndFlush(Unpooled.buffer().writeZero(12), promise);
        pending[0].cancel(false); assertEquals("0", p.sample().traffic().outbound().totalBytes());
        channel.finishAndReleaseAll();
    }
    @Test void instrumentationIsIdempotentAndDoesNotCloseThePlatformChannel() {
        var p = profiler(); var instrumentation = new ChannelInstrumentation(p); var channel = new EmbeddedChannel();
        instrumentation.install(channel); instrumentation.install(channel); channel.runPendingTasks();
        channel.writeInbound(Unpooled.buffer().writeZero(10));
        assertEquals("10", p.sample().traffic().inbound().totalBytes()); assertEquals(1, instrumentation.connections());
        instrumentation.close(); assertTrue(channel.isOpen()); assertNull(channel.pipeline().get(ChannelInstrumentation.NAME));
        channel.writeInbound(Unpooled.buffer().writeZero(20)); assertEquals("10", p.sample().traffic().inbound().totalBytes());
        channel.finishAndReleaseAll();
    }
    @Test void conflictingInstrumentationIsRejectedInsteadOfSilentlyReportingZero() {
        var p = profiler(); var channel = new EmbeddedChannel();
        var first = new ChannelInstrumentation(p); var second = new ChannelInstrumentation(p);
        first.install(channel);
        assertThrows(IllegalStateException.class, () -> second.install(channel));
        second.close(); assertNotNull(channel.pipeline().get(ChannelInstrumentation.NAME));
        first.close(); channel.finishAndReleaseAll();
    }
}
