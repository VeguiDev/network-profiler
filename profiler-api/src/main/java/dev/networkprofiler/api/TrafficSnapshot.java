package dev.networkprofiler.api;

/** Rates are bytes/second. Null rates mean a baseline or unavailable direction, not measured zero. */
public record TrafficSnapshot(
        String sessionId, long sequence, long timestamp, double intervalMs, long uptimeMs,
        Traffic traffic, Integer players) {
    public TrafficSnapshot(String sessionId, long sequence, long timestamp, double intervalMs, long uptimeMs, Traffic traffic) {
        this(sessionId, sequence, timestamp, intervalMs, uptimeMs, traffic, null);
    }
    public record Direction(boolean available, String totalBytes, Double bytesPerSecond, Double peakBytesPerSecond) {}
    public record Traffic(Direction inbound, Direction outbound, String combinedTotalBytes) {}
}
