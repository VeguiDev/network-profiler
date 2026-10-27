package dev.networkprofiler.velocity;

import com.github.retrooper.packetevents.util.reflection.Reflection;
import dev.networkprofiler.core.TrafficProfiler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeVelocityProviderTest {
    public static final class Holder {
        private ChannelInitializer<Channel> initializer;
        Holder(ChannelInitializer<Channel> initializer) { this.initializer = initializer; }
        public ChannelInitializer<Channel> get() { return initializer; }
        public void set(ChannelInitializer<Channel> initializer) { this.initializer = initializer; }
    }
    public static final class Manager {
        final Holder holder;
        Manager(ChannelInitializer<Channel> initializer) { holder = new Holder(initializer); }
        public Holder getServerChannelInitializer() { return holder; }
    }
    public static final class Proxy {
        private final Manager cm;
        Proxy(ChannelInitializer<Channel> initializer) { cm = new Manager(initializer); }
    }
    private static final class ForeignInitializer extends ChannelInitializer<Channel> {
        private final ChannelInitializer<Channel> delegate;
        ForeignInitializer(ChannelInitializer<Channel> delegate) { this.delegate = delegate; }
        @Override protected void initChannel(Channel channel) { channel.pipeline().addLast(delegate); }
    }
    private static ChannelInitializer<Channel> original() {
        return new ChannelInitializer<>() { @Override protected void initChannel(Channel channel) {} };
    }
    @Test void packetEventsCanUnwrapFirstWithoutBeingResurrectedByOurShutdown() throws Exception {
        var original = original(); var foreign = new ForeignInitializer(original); var proxy = new Proxy(foreign);
        try (var profiler = new TrafficProfiler(900, true, true);
             var provider = new NativeVelocityProvider(proxy, profiler)) {
            provider.start(); var wrapper = proxy.cm.holder.get();
            // Use the exact field lookup performed by PacketEvents 2.14's uninjector.
            var delegate = Reflection.getField(wrapper.getClass(), ChannelInitializer.class, 0);
            assertNotNull(delegate); delegate.setAccessible(true); assertSame(foreign, delegate.get(wrapper));
            delegate.set(wrapper, original);
            assertSame(wrapper, proxy.cm.holder.get());
            provider.close(); assertSame(original, proxy.cm.holder.get());
        }
    }
    @Test void ourShutdownCanRunBeforePacketEventsShutdown() throws Exception {
        var original = original(); var foreign = new ForeignInitializer(original); var proxy = new Proxy(foreign);
        try (var profiler = new TrafficProfiler(900, true, true);
             var provider = new NativeVelocityProvider(proxy, profiler)) {
            provider.start(); provider.close(); assertSame(foreign, proxy.cm.holder.get());
            // PacketEvents now sees its own wrapper directly and restores its delegate.
            proxy.cm.holder.set(foreign.delegate); assertSame(original, proxy.cm.holder.get());
        }
    }
    @Test void neverOverwritesAnInitializerInstalledAfterOurs() throws Exception {
        var proxy = new Proxy(original());
        try (var profiler = new TrafficProfiler(900, true, true);
             var provider = new NativeVelocityProvider(proxy, profiler)) {
            provider.start(); var later = new ForeignInitializer(proxy.cm.holder.get());
            proxy.cm.holder.set(later); provider.close(); assertSame(later, proxy.cm.holder.get());
        }
    }
}
