package dev.vegui.vnp.netty;

import dev.vegui.vnp.api.TrafficSource;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.*;

/** Counts transport write successes, NOT remote acknowledgements. No buffer captured by the listener. */
public final class TrafficCountingHandler extends ChannelDuplexHandler {
    private final TrafficSource source;
    // Counter updates are event-loop confined; volatile permits diagnostic reads from another thread.
    private volatile long readEvents, writeEvents;
    public TrafficCountingHandler(TrafficSource source) { this.source = source; }
    public long readEvents() { return readEvents; }
    /** Write attempts, including failures; these are not confirmed bytes or Minecraft packets. */
    public long writeEvents() { return writeEvents; }
    private static int size(Object message) {
        if (message instanceof ByteBuf buffer) return buffer.readableBytes();
        if (message instanceof ByteBufHolder holder) return holder.content().readableBytes();
        return 0;
    }
    @Override public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
        readEvents++;
        source.collector().received(size(message));
        ctx.fireChannelRead(message);
    }
    @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
        writeEvents++;
        int bytes = size(message);
        var collector = source.collector();
        ChannelPromise observable = promise.unvoid();
        if (bytes > 0) observable.addListener(future -> { if (future.isSuccess()) collector.sent(bytes); });
        ctx.write(message, observable);
    }
}
