package dev.networkprofiler.server;

import java.util.Set;

public record ServerOptions(String host, int port, Set<String> allowedHosts, Set<String> allowedOrigins, int maxClients) {
    public ServerOptions {
        if (host == null || host.isBlank() || port < 0 || port > 65535 || maxClients < 1 || maxClients > 256)
            throw new IllegalArgumentException("Invalid HTTP options");
        allowedHosts = Set.copyOf(allowedHosts); allowedOrigins = Set.copyOf(allowedOrigins);
        if (allowedHosts.isEmpty()) throw new IllegalArgumentException("allowed-hosts must not be empty");
    }
    public static ServerOptions local(int port) {
        return new ServerOptions("127.0.0.1", port, Set.of("127.0.0.1", "localhost", "[::1]"), Set.of(), 16);
    }
}
