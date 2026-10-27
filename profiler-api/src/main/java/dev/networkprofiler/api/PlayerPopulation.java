package dev.networkprofiler.api;

import java.util.Map;

/** A point-in-time count of authenticated proxy players, never TCP connections. */
public record PlayerPopulation(Integer global, Map<String, Integer> servers, Map<String, String> players,
                               Map<String, String> connections, boolean complete) {
    public PlayerPopulation {
        servers = Map.copyOf(servers); players = Map.copyOf(players); connections = Map.copyOf(connections);
    }
    public static PlayerPopulation unavailable() { return new PlayerPopulation(null, Map.of(), Map.of(), Map.of(), false); }
    public int entries() { return servers.size() + players.size() + connections.size(); }
}
