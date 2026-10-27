package dev.networkprofiler.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.*;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.networkprofiler.api.*;
import dev.networkprofiler.core.TrafficProfiler;
import dev.networkprofiler.server.ProfilerServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import java.nio.file.Path;
import java.util.Set;

@Plugin(id = "networkprofiler", name = "Minecraft Network Profiler", version = "0.1.0",
        description = "Client-side transport traffic profiler", authors = {"Network Profiler contributors"},
        dependencies = {@Dependency(id = "packetevents", optional = true)})
public final class NetworkProfilerPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private TrafficProfiler profiler;
    private ProfilerServer server;
    @Inject public NetworkProfilerPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        this.proxy = proxy; this.logger = logger; this.directory = directory;
    }
    @Subscribe(order = PostOrder.LAST)
    public void initialize(ProxyInitializeEvent event) {
        try {
            var config = ProfilerConfig.load(directory);
            profiler = new TrafficProfiler(config.historySeconds(), config.inbound(), config.outbound());
            if (!config.enabled() || !config.trafficEnabled() || (!config.inbound() && !config.outbound())) {
                profiler.disabled("Collection disabled by configuration");
            } else {
                if (!proxy.getVersion().getVersion().startsWith("4.3.0"))
                    throw new IllegalStateException("This release validates Velocity 4.3.0 only; found " + proxy.getVersion().getVersion());
                if (!proxy.getAllPlayers().isEmpty()) throw new IllegalStateException("Hot loading is unsupported; restart the proxy");
                if (config.provider().equals("packetevents")) {
                    if (proxy.getPluginManager().getPlugin("packetevents").isEmpty()) throw new IllegalStateException("Install PacketEvents or select native channel-provider");
                    profiler.register(new PacketEventsProvider(profiler));
                } else profiler.register(new NativeVelocityProvider(proxy, profiler));
                try { profiler.start(); } catch (Exception failure) { logger.error("Traffic instrumentation failed", failure); }
            }
            if (config.httpEnabled()) {
                var platform = new NetworkPlatform() {
                    public String name() { return "Velocity"; }
                    public String version() { return proxy.getVersion().getVersion(); }
                    public Set<ProfilerCapability> capabilities() { return profiler.capabilities(); }
                };
                server = new ProfilerServer(profiler, platform, "0.1.0", config.http()); server.start();
                logger.info("Network dashboard: http://{}:{}", config.http().host(), server.port());
                if (!config.http().host().equals("127.0.0.1") && !config.http().host().equals("::1"))
                    logger.warn("HTTP is not loopback-only. This service has no authentication; restrict access with a firewall and authenticated reverse proxy.");
            }
            var meta = proxy.getCommandManager().metaBuilder("networkprofiler").plugin(this).build();
            proxy.getCommandManager().register(meta, new SimpleCommand() {
                public boolean hasPermission(Invocation invocation) { return invocation.source().hasPermission("networkprofiler.admin"); }
                public void execute(Invocation invocation) {
                    String action = invocation.arguments().length == 0 ? "status" : invocation.arguments()[0];
                    if (action.equalsIgnoreCase("reset")) {
                        profiler.reset(); invocation.source().sendMessage(Component.text("Network profiler session reset."));
                    } else invocation.source().sendMessage(Component.text("Network profiler: " + profiler.state() + " — " + profiler.detail()));
                }
            });
        } catch (Exception | LinkageError failure) {
            closeResources();
            if (profiler != null) profiler.fail("Startup failed: " + failure.getMessage());
            logger.error("Network profiler could not start; metrics are unavailable", failure);
        }
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event) {
        closeResources();
        proxy.getCommandManager().unregister("networkprofiler");
    }
    private void closeResources() {
        try { if (server != null) server.close(); }
        catch (RuntimeException failure) { logger.error("Could not completely stop the HTTP server", failure); }
        finally { if (profiler != null) profiler.close(); }
    }
}
