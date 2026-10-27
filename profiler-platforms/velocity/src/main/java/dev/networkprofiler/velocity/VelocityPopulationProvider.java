package dev.networkprofiler.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import dev.networkprofiler.api.*;
import dev.networkprofiler.core.PacketStatistics;
import java.util.*;

/** Called by samplers, not by per-packet callbacks. Counts actual proxy players, not tracked sockets. */
final class VelocityPopulationProvider implements PopulationProvider {
    private final ProxyServer proxy;
    private final PacketStatistics statistics;
    VelocityPopulationProvider(ProxyServer proxy, PacketStatistics statistics) { this.proxy = proxy; this.statistics = statistics; }
    public PlayerPopulation snapshot(Set<PacketContext> contexts) {
        var servers = new HashMap<String, Integer>();
        var online = new HashMap<String, String>();
        // Registered but empty servers are measured zero, absent/disconnected identities are unknown.
        proxy.getAllServers().forEach(s -> servers.put(s.getServerInfo().getName(), 0));
        var all = proxy.getAllPlayers();
        for (var p : all) p.getCurrentServer().ifPresent(s -> {
            String server = s.getServerInfo().getName(); servers.merge(server, 1, Integer::sum);
            online.put(p.getUniqueId().toString(), server);
        });
        var players = new HashMap<String, String>(); var connections = new HashMap<String, String>();
        for (var context : contexts) {
            if (context.playerId() != null && online.containsKey(context.playerId())) players.put(context.playerId(), online.get(context.playerId()));
            var active = statistics.activeContext(context.connectionId());
            if (active != null && active.playerId() != null && online.containsKey(active.playerId()))
                connections.put(context.connectionId(), online.get(active.playerId()));
        }
        return new PlayerPopulation(all.size(), servers, players, connections, true);
    }
}
