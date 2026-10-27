package dev.vegui.vnp.velocity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.UserConnectEvent;
import dev.vegui.vnp.api.*;
import dev.vegui.vnp.core.TrafficProfiler;
import dev.vegui.vnp.netty.ChannelInstrumentation;
import io.netty.channel.Channel;
import java.util.Set;

/** Only loaded when explicitly selected and PacketEvents is installed. Never owns its global lifecycle. */
final class PacketEventsProvider implements MetricProvider {
    private final ChannelInstrumentation instrumentation;
    private final TrafficProfiler profiler;
    private final PacketListenerAbstract listener = new PacketListenerAbstract() {
        @Override public void onUserConnect(UserConnectEvent event) { install(event.getUser().getChannel()); }
    };
    PacketEventsProvider(TrafficProfiler profiler) { this.profiler = profiler; instrumentation = new ChannelInstrumentation(profiler); }
    private void install(Object channel) {
        try {
            if (!(channel instanceof Channel compatible)) throw new IllegalStateException("PacketEvents channel is not compatible with host Netty");
            instrumentation.install(compatible);
        } catch (RuntimeException failure) { profiler.fail("PacketEvents instrumentation failed: " + failure.getMessage()); }
    }
    public String id() { return "velocity-packetevents"; }
    public Set<ProfilerCapability> capabilities() { return Set.of(ProfilerCapability.TOTAL_TRAFFIC); }
    public void start() {
        var api = PacketEvents.getAPI();
        if (api == null || !api.isInitialized()) throw new IllegalStateException("PacketEvents must be initialized first");
        // Existing channels cannot provide bytes before installation. Normal supported deployment is proxy startup.
        if (!api.getProtocolManager().getUsers().isEmpty()) throw new IllegalStateException("Start the profiler with the proxy, not after clients connect");
        api.getEventManager().registerListener(listener);
    }
    public void close() {
        var api = PacketEvents.getAPI();
        if (api != null) api.getEventManager().unregisterListener(listener);
        instrumentation.close();
    }
}
