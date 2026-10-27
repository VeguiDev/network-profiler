package dev.networkprofiler.velocity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.protocol.packettype.*;
import dev.networkprofiler.api.*;
import dev.networkprofiler.netty.*;
import dev.networkprofiler.core.TrafficProfiler;
import dev.networkprofiler.core.PacketStatistics;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.util.*;

/** Validated against Velocity 4.3.0 / PacketEvents 2.14.0. No packet wrappers or buffer mutations. */
final class PacketEventsCaptureProvider implements PacketCaptureProvider, AutoCloseable {
    private static final AttributeKey<PacketStatistics.Connection> CONNECTION = AttributeKey.valueOf("network-profiler-association");
    private static final AttributeKey<Boolean> REJECTED = AttributeKey.valueOf("network-profiler-association-limit");
    private final List<PacketCaptureReport.Descriptor> descriptors = new ArrayList<>();
    private final Map<PacketTypeCommon, Integer> indices = new IdentityHashMap<>();
    private PacketListenerAbstract listener;
    private final TrafficProfiler profiler;
    private final PacketStatistics statistics;
    private final ProxyServer proxy;
    private final Object owner;
    private volatile Recorder recorder;
    private volatile boolean closed;
    PacketEventsCaptureProvider(TrafficProfiler profiler, PacketStatistics statistics, ProxyServer proxy, Object owner) {
        this.profiler = profiler; this.statistics = statistics; this.proxy = proxy; this.owner = owner;
        var api = PacketEvents.getAPI();
        if (api == null || !api.isInitialized() || !api.getVersion().toStringWithoutSnapshot().equals("2.14.0"))
            throw new IllegalStateException("Packet analysis requires external PacketEvents 2.14.0 initialized on Velocity");
        add(PacketType.Handshaking.Client.values(), "HANDSHAKING", TrafficDirection.INBOUND);
        add(PacketType.Handshaking.Server.values(), "HANDSHAKING", TrafficDirection.OUTBOUND);
        add(PacketType.Status.Client.values(), "STATUS", TrafficDirection.INBOUND);
        add(PacketType.Status.Server.values(), "STATUS", TrafficDirection.OUTBOUND);
        add(PacketType.Login.Client.values(), "LOGIN", TrafficDirection.INBOUND);
        add(PacketType.Login.Server.values(), "LOGIN", TrafficDirection.OUTBOUND);
        add(PacketType.Configuration.Client.values(), "CONFIGURATION", TrafficDirection.INBOUND);
        add(PacketType.Configuration.Server.values(), "CONFIGURATION", TrafficDirection.OUTBOUND);
        add(PacketType.Play.Client.values(), "PLAY", TrafficDirection.INBOUND);
        add(PacketType.Play.Server.values(), "PLAY", TrafficDirection.OUTBOUND);
        proxy.getEventManager().register(owner, this);
        if (statistics.enabled()) register();
    }
    private <E extends Enum<E> & PacketTypeCommon> void add(E[] types, String state, TrafficDirection direction) {
        for (var type : types) {
            int index = descriptors.size(); indices.put(type, index);
            descriptors.add(new PacketCaptureReport.Descriptor(index, type.name(), state, direction));
        }
    }
    public List<PacketCaptureReport.Descriptor> descriptors() { return List.copyOf(descriptors); }
    public boolean available() {
        var api = PacketEvents.getAPI();
        return !closed && profiler.healthy() && api != null && api.isInitialized() && !api.isTerminated();
    }
    public synchronized void start(Recorder recorder) {
        if (!available()) throw new IllegalStateException("Packet source unavailable; check transport health and PacketEvents");
        if (this.recorder != null) throw new IllegalStateException("Packet capture already running");
        register(); this.recorder = recorder;
    }
    private synchronized void register() {
        if (listener != null) return;
        listener = new PacketListenerAbstract(PacketListenerPriority.MONITOR) {
            public void onPacketReceive(PacketReceiveEvent event) { observe(event, TrafficDirection.INBOUND); }
            public void onPacketSend(PacketSendEvent event) { observe(event, TrafficDirection.OUTBOUND); }
        };
        PacketEvents.getAPI().getEventManager().registerListener(listener);
    }
    private void observe(ProtocolPacketEvent event, TrafficDirection direction) {
        // Only client channels instrumented by us. Never include proxy -> backend pipelines.
        if (!(event.getChannel() instanceof Channel channel)
                || !(channel.pipeline().get(ChannelInstrumentation.NAME) instanceof TrafficCountingHandler)) return;
        if (closed) return;
        var capture = recorder;
        var connection = connection(channel);
        var association = connection == null ? null : connection.association();
        var context = association == null ? PacketContext.UNKNOWN : association.context();
        if (!(event.getByteBuf() instanceof ByteBuf buffer)) { statistics.error(); if (capture != null) capture.record(context, -1, 0, false, false); return; }
        int bytes = observedBytes(buffer);
        if (bytes < 0) { statistics.error(); if (capture != null) capture.record(context, -1, 0, false, false); return; }
        if (association != null) association.record(direction, bytes, event.isCancelled());
        else if (!event.isCancelled()) statistics.untracked(bytes);
        // PE 2.14 Velocity copies into a zero-based buffer, consumes the ID, and restores the
        // post-ID reader index between listeners. writerIndex includes the actual encoded ID.
        // This is a MONITOR observation, before any subsequent wrapper re-encoding or write.
        // No catalog lookup/type classification in the permanent statistics path.
        if (capture != null) {
            var index = indices.get(event.getPacketType());
            if (index == null) capture.record(context, -1, 0, false, false);
            else capture.record(context, index, bytes, event.isCancelled(), event.needsReEncode() && event.getLastUsedWrapper() != null);
        }
    }
    private PacketStatistics.Connection connection(Channel channel) {
        var connection = channel.attr(CONNECTION).get();
        if (connection != null) return connection;
        if (Boolean.TRUE.equals(channel.attr(REJECTED).get())) return null;
        // PE callbacks and this creation are confined to the client channel event loop.
        connection = statistics.open();
        if (connection == null) { channel.attr(REJECTED).set(true); return null; }
        channel.attr(CONNECTION).set(connection);
        var registered = connection;
        channel.closeFuture().addListener(ignored -> {
            registered.disconnect();
            var activeCapture = recorder;
            if (activeCapture != null) activeCapture.connectionClosed(registered.id());
            channel.attr(CONNECTION).set(null);
        });
        return connection;
    }
    @Subscribe(order = PostOrder.LAST)
    public EventTask login(PostLoginEvent event) { return associate(event.getPlayer(), null); }
    @Subscribe(order = PostOrder.LAST)
    public EventTask connected(ServerConnectedEvent event) { return associate(event.getPlayer(), event.getServer().getServerInfo().getName()); }
    private EventTask associate(Player player, String server) {
        Object raw = PacketEvents.getAPI().getProtocolManager().getChannel(player.getUniqueId());
        if (!(raw instanceof Channel channel)) { statistics.error(); return null; }
        return EventTask.withContinuation(continuation -> {
            try {
                channel.eventLoop().execute(() -> {
                    try {
                        if (!closed && channel.isOpen()) {
                            var c = connection(channel);
                            if (c != null) c.associate(player.getUniqueId().toString(), player.getUsername(), server == null ? c.context().serverId() : server);
                        }
                        continuation.resume();
                    } catch (RuntimeException failure) { statistics.error(); continuation.resume(); }
                });
            } catch (java.util.concurrent.RejectedExecutionException stopped) { statistics.error(); continuation.resume(); }
        });
    }
    static int observedBytes(ByteBuf buffer) {
        int reader = buffer.readerIndex(), writer = buffer.writerIndex();
        return reader >= 1 && reader <= 5 && writer >= reader ? writer : -1;
    }
    public synchronized void stop() {
        recorder = null;
        if (!statistics.enabled()) unregister();
    }
    private void unregister() {
        if (listener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener); listener = null;
        }
    }
    @Override public synchronized void close() {
        closed = true; recorder = null; unregister(); proxy.getEventManager().unregisterListener(owner, this);
    }
}
