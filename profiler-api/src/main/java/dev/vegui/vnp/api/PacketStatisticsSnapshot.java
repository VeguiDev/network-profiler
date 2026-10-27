package dev.vegui.vnp.api;

import java.util.List;

/** Protocol statistics. Rates are averages of the actual aggregation interval, never wire throughput. */
public record PacketStatisticsSnapshot(long timestamp, double intervalMs, boolean ready, boolean complete,
                                       String observationErrors, String untrackedBytes, List<Row> servers, List<Row> connections) {
    public PacketStatisticsSnapshot { servers = List.copyOf(servers); connections = List.copyOf(connections); }
    public record Row(String id, String playerId, String playerName, String serverId, boolean active,
                      int connections, String inboundBytes, String outboundBytes, String totalBytes,
                      String intervalInboundBytes, String intervalOutboundBytes,
                      Double inboundBytesPerSecond, Double outboundBytesPerSecond) {}
}
