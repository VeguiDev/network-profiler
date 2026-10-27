package dev.vegui.vnp.velocity;

import dev.vegui.vnp.api.*;
import dev.vegui.vnp.core.TrafficProfiler;
import dev.vegui.vnp.netty.ChannelInstrumentation;
import io.netty.channel.*;
import java.lang.reflect.*;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Version-specific bridge for Velocity 4.3.0. Reflection is deliberately confined to this class. */
final class NativeVelocityProvider implements MetricProvider {
    private final Object proxy;
    private final TrafficProfiler profiler;
    private final ChannelInstrumentation instrumentation;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private Object holder;
    private Method getter, setter;
    private ClientInitializer wrapper;
    NativeVelocityProvider(Object proxy, TrafficProfiler profiler) {
        this.proxy = proxy; this.profiler = profiler; instrumentation = new ChannelInstrumentation(profiler);
    }
    public String id() { return "velocity-native"; }
    public Set<ProfilerCapability> capabilities() { return Set.of(ProfilerCapability.TOTAL_TRAFFIC); }
    @SuppressWarnings("unchecked") public void start() throws Exception {
        Field field = proxy.getClass().getDeclaredField("cm"); field.setAccessible(true);
        Object manager = field.get(proxy);
        holder = manager.getClass().getMethod("getServerChannelInitializer").invoke(manager);
        getter = holder.getClass().getMethod("get");
        setter = holder.getClass().getMethod("set", ChannelInitializer.class);
        Object candidate = getter.invoke(holder);
        if (!(candidate instanceof ChannelInitializer<?>)) throw new IllegalStateException("Unexpected client initializer type");
        wrapper = new ClientInitializer((ChannelInitializer<Channel>) candidate, profiler, instrumentation, active);
        setter.invoke(holder, wrapper);
        if (getter.invoke(holder) != wrapper) throw new IllegalStateException("Client initializer replacement was not retained");
    }
    /** Mutable delegate lets PacketEvents 2.14 unwrap its own initializer without replacing ours. */
    private static final class ClientInitializer extends ChannelInitializer<Channel> {
        private volatile ChannelInitializer<Channel> delegate;
        private final TrafficProfiler profiler;
        private final ChannelInstrumentation instrumentation;
        private final AtomicBoolean active;
        ClientInitializer(ChannelInitializer<Channel> delegate, TrafficProfiler profiler,
                          ChannelInstrumentation instrumentation, AtomicBoolean active) {
            this.delegate = delegate; this.profiler = profiler;
            this.instrumentation = instrumentation; this.active = active;
        }
        @Override protected void initChannel(Channel channel) {
            // Do not invoke protected initChannel reflectively. A registered channel executes an added initializer immediately.
            channel.pipeline().addLast(delegate);
            if (!active.get()) return;
            boolean decoder = channel.pipeline().toMap().values().stream().anyMatch(handler ->
                    handler.getClass().getName().equals("com.velocitypowered.proxy.protocol.netty.MinecraftDecoder"));
            if (!decoder) { profiler.fail("Client pipeline did not match validated Velocity 4.3.0 layout"); return; }
            try { instrumentation.install(channel); }
            catch (RuntimeException failure) { profiler.fail("Client instrumentation failed: " + failure.getMessage()); }
        }
    }
    @Override public void close() {
        active.set(false);
        if (holder != null && wrapper != null) {
            try {
                // Never overwrite an initializer installed by another plugin after ours.
                // Use the current delegate: another plugin may already have unwrapped itself.
                if (getter.invoke(holder) == wrapper) setter.invoke(holder, wrapper.delegate);
            } catch (ReflectiveOperationException failure) { profiler.fail("Could not restore client initializer"); }
        }
        instrumentation.close();
    }
}
