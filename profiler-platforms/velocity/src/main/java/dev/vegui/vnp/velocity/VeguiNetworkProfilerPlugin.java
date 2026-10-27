package dev.vegui.vnp.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.*;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.vegui.vnp.api.*;
import dev.vegui.vnp.core.TrafficProfiler;
import dev.vegui.vnp.core.PacketCaptures;
import dev.vegui.vnp.core.PacketStatistics;
import dev.vegui.vnp.server.FileReportStore;
import dev.vegui.vnp.server.ProfilerService;
import dev.vegui.vnp.relay.RelayClient;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import java.nio.file.Path;
import java.util.Set;

@Plugin(id = "vnp", name = "Vegui Network Profiler", version = PluginBuildVersion.VERSION,
        description = "Client-side transport traffic profiler", authors = {"VeguiDev"},
        dependencies = {@Dependency(id = "packetevents", optional = true)})
public final class VeguiNetworkProfilerPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private TrafficProfiler profiler;
    private RelayClient relay;
    private PacketCaptures captures;
    private PacketStatistics statistics;
    private PacketCaptureProvider packetProvider;
    @Inject public VeguiNetworkProfilerPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        this.proxy = proxy; this.logger = logger; this.directory = directory;
    }
    @Subscribe(order = PostOrder.LAST)
    public void initialize(ProxyInitializeEvent event) {
        try {
            var config = ProfilerConfig.load(directory);
            profiler = new TrafficProfiler(config.historySeconds(), config.inbound(), config.outbound());
            profiler.playerCount(() -> proxy.getPlayerCount());
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
            statistics = new PacketStatistics(config.statistics());
            proxy.getAllServers().forEach(s -> statistics.registerServer(s.getServerInfo().getName()));
            if ((config.capturesEnabled() || config.statistics().enabled()) && profiler.healthy()) {
                if (proxy.getPluginManager().getPlugin("packetevents").isPresent()) {
                    try { packetProvider = new PacketEventsCaptureProvider(profiler, statistics, proxy, this); }
                    catch (RuntimeException | LinkageError unsupported) { logger.warn("Packet analysis unavailable: {}", unsupported.getMessage()); }
                } else logger.warn("Packet analysis requires PacketEvents 2.14.0 installed as a separate Velocity plugin. Transport metrics remain available.");
            }
            if (packetProvider != null && statistics.enabled()) statistics.start();
            captures = new PacketCaptures(config.capturesEnabled() ? packetProvider : null, new FileReportStore(directory.resolve("reports"), config.captures()),
                    config.captures(), dev.vegui.vnp.core.TimeSource.SYSTEM, new VelocityPopulationProvider(proxy, statistics));
            if (config.relay().enabled()) {
                var platform = new NetworkPlatform() {
                    public String name() { return "Velocity"; }
                    public String version() { return proxy.getVersion().getVersion(); }
                    public Set<ProfilerCapability> capabilities() {
                        var result = new java.util.HashSet<>(profiler.capabilities());
                         if (captures.available()) result.add(ProfilerCapability.PACKET_TRAFFIC);
                         if (statistics.enabled() && packetProvider != null && packetProvider.available())
                             result.addAll(Set.of(ProfilerCapability.CONNECTION_TRAFFIC, ProfilerCapability.PLAYER_TRAFFIC, ProfilerCapability.SERVER_ATTRIBUTION));
                        return Set.copyOf(result);
                    }
                };
                try {
                    var service = new ProfilerService(profiler, platform, PluginBuildVersion.VERSION, captures, statistics,
                            () -> packetProvider != null && packetProvider.available(), new dev.vegui.vnp.server.DashboardSettings(directory.resolve("dashboard-settings.json")));
                    relay = new RelayClient(config.relay(), directory.resolve("relay-identity.json"), profiler, service, logger::warn);
                    relay.start();
                    logger.info("Remote dashboard enabled (outgoing only). Use /vnp dashboard; approve each browser with /vnp trustSession <code>.");
                } catch (Exception failure) { logger.warn("Remote dashboard unavailable; local collection remains active: {}", failure.getClass().getSimpleName()); }
            }
            var meta = proxy.getCommandManager().metaBuilder("vnp").plugin(this).build();
            proxy.getCommandManager().register(meta, new SimpleCommand() {
                public boolean hasPermission(Invocation invocation) {
                    return invocation.source().hasPermission("vnp.admin");
                }
                public void execute(Invocation invocation) {
                    String action = invocation.arguments().length == 0 ? "status" : invocation.arguments()[0];
                    try {
                    if (action.equalsIgnoreCase("start")) {
                        int duration = captures.maxDurationSeconds(), offset = 1;
                        var args = invocation.arguments();
                        if (args.length > offset && !args[offset].startsWith("--")) duration = Integer.parseInt(args[offset++]);
                        CaptureScope scope = CaptureScope.GLOBAL;
                        if (args.length > offset) {
                            if (args.length != offset + 2) throw new IllegalArgumentException("Expected --server <name>, --player <name|uuid> or --connection <id>");
                            String id = args[offset + 1];
                            scope = switch (args[offset]) {
                                case "--server" -> { if (proxy.getServer(id).isEmpty() && !statistics.containsServer(id)) throw new IllegalArgumentException("Unknown server"); yield new CaptureScope("SERVER", id); }
                                case "--player" -> { var p = proxy.getPlayer(id); if (p.isPresent()) id = p.get().getUniqueId().toString();
                                    if (!statistics.containsPlayer(id)) throw new IllegalArgumentException("Player is not tracked or is offline"); yield new CaptureScope("PLAYER", id); }
                                case "--connection" -> { if (!statistics.containsConnection(id)) throw new IllegalArgumentException("Connection is not active"); yield new CaptureScope("CONNECTION", id); }
                                default -> throw new IllegalArgumentException("Unknown scope option");
                            };
                        }
                        var report = captures.start(duration, scope);
                        invocation.source().sendMessage(Component.text("VNP recording started: " + report.id() + ". /vnp stop to finish."));
                        sendReportLink(invocation.source(), report.id());
                    } else if (action.equalsIgnoreCase("stop")) {
                        var active = captures.current();
                        if (active == null) throw new IllegalStateException("No capture is running");
                        invocation.source().sendMessage(Component.text("Finalizing packet report…"));
                        captures.stop(active.id()).whenComplete((report, error) -> {
                            if (error != null) invocation.source().sendMessage(Component.text("Report failed: " + error.getMessage(), NamedTextColor.RED));
                            else {
                                invocation.source().sendMessage(Component.text("VNP " + report.state() + " | " + report.durationMs() / 1000d + "s | IN "
                                        + report.inboundBytes() + " B | OUT " + report.outboundBytes() + " B | " + report.packetCount() + " packets (protocol observations)."));
                                sendReportLink(invocation.source(), report.id());
                            }
                        });
                    } else if (action.equalsIgnoreCase("dashboard")) {
                        if (relay == null) throw new IllegalStateException("Relay is disabled or unavailable");
                        invocation.source().sendMessage(Component.text("Connecting to dashboard relay…"));
                        relay.dashboard().whenComplete((url, error) -> {
                            if (error != null) invocation.source().sendMessage(Component.text("Dashboard unavailable; local collection continues. " + relay.status(), NamedTextColor.RED));
                            else invocation.source().sendMessage(Component.text("Open dashboard: " + url, NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(url)));
                        });
                    } else if (action.equalsIgnoreCase("trustSession")) {
                        if (relay == null || invocation.arguments().length != 2) throw new IllegalArgumentException("Usage: /vnp trustSession <browser code>");
                        relay.trust(invocation.arguments()[1]).whenComplete((v, error) -> invocation.source().sendMessage(Component.text(
                                error == null ? "Browser approved with full dashboard access." : "Approval failed: unknown, expired or already used code / relay unavailable.", error == null ? NamedTextColor.GREEN : NamedTextColor.RED)));
                    } else if (action.equalsIgnoreCase("reset")) {
                        profiler.reset(); invocation.source().sendMessage(Component.text("VNP session reset."));
                    } else if (action.equalsIgnoreCase("status")) {
                        var active = captures.current();
                        invocation.source().sendMessage(Component.text("VNP: " + profiler.state() + " — " + profiler.detail()
                                + " | Packets: " + (active == null ? captures.detail() : "recording " + active.id() + " (" + active.durationMs() / 1000 + "s)") + " | Relay: " + (relay == null ? "disabled" : relay.status())));
                    } else invocation.source().sendMessage(Component.text("Usage: /vnp dashboard | trustSession <code> | start [seconds] [--server <name> | --player <name|uuid> | --connection <id>] | stop | status | reset"));
                    } catch (IllegalArgumentException | IllegalStateException invalid) {
                        invocation.source().sendMessage(Component.text(invalid.getMessage(), NamedTextColor.RED));
                    }
                }
            });
        } catch (Exception | LinkageError failure) {
            closeResources();
            if (profiler != null) profiler.fail("Startup failed: " + failure.getMessage());
            logger.error("VNP could not start; metrics are unavailable", failure);
        }
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event) {
        closeResources();
        proxy.getCommandManager().unregister("vnp");
    }
    private void sendReportLink(com.velocitypowered.api.command.CommandSource source, String id) {
        source.sendMessage(Component.text("Local report " + id + " — open Reports in an approved /vnp dashboard session."));
    }
    private void closeResources() {
        try { if (relay != null) relay.close(); }
        catch (RuntimeException failure) { logger.error("Could not completely stop the relay client", failure); }
        finally {
            try { if (captures != null) captures.close(); }
            finally {
                try { if (packetProvider instanceof AutoCloseable source) source.close(); }
                catch (Exception failure) { logger.error("Could not stop packet observation", failure); }
                finally { if (statistics != null) statistics.close(); if (profiler != null) profiler.close(); }
            }
        }
    }
}
