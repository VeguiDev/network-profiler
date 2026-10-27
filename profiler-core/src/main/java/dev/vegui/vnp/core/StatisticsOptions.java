package dev.vegui.vnp.core;

public record StatisticsOptions(boolean enabled, int intervalSeconds, int maxConnections, int maxServers, int disconnectedRetentionSeconds) {
    public StatisticsOptions {
        if (intervalSeconds < 1 || intervalSeconds > 300 || maxConnections < 1 || maxConnections > 100000
                || maxServers < 1 || maxServers > 10000 || disconnectedRetentionSeconds < 0 || disconnectedRetentionSeconds > 86400)
            throw new IllegalArgumentException("Invalid statistics limits");
    }
    public static StatisticsOptions defaults() { return new StatisticsOptions(true, 30, 10000, 1000, 900); }
}
