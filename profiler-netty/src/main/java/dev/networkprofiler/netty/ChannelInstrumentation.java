package dev.networkprofiler.netty;

import dev.networkprofiler.api.TrafficSource;
import io.netty.channel.Channel;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Never closes platform channels or their event loops. */
public final class ChannelInstrumentation implements AutoCloseable {
    public static final String NAME = "network-profiler-transport";
    private final Set<Channel> channels = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final TrafficSource source;
    public ChannelInstrumentation(TrafficSource source) { this.source = source; }
    public void install(Channel channel) {
        Runnable install = () -> {
            if (!active.get() || !channel.isOpen()) return;
            if (channel.pipeline().get(NAME) != null) {
                if (!channels.contains(channel)) throw new IllegalStateException("Transport handler name is already owned by another instrumenter");
                return;
            }
            channel.pipeline().addFirst(NAME, new TrafficCountingHandler(source));
            channels.add(channel);
            channel.closeFuture().addListener(ignored -> channels.remove(channel));
        };
        if (channel.eventLoop().inEventLoop()) install.run(); else channel.eventLoop().execute(install);
    }
    public int connections() { return channels.size(); }
    @Override public void close() {
        active.set(false);
        for (Channel channel : channels) {
            Runnable remove = () -> { if (channel.pipeline().get(NAME) instanceof TrafficCountingHandler) channel.pipeline().remove(NAME); };
            try {
                if (channel.eventLoop().inEventLoop()) remove.run();
                else channel.eventLoop().submit(remove).awaitUninterruptibly(2000);
            } catch (RejectedExecutionException ignored) { /* Closed platform loop. */ }
        }
        channels.clear();
    }
}
