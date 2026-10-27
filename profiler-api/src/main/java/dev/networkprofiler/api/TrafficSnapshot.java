package dev.networkprofiler.api;

/** Rates are bytes/second. Null rates mean a baseline or unavailable direction, not measured zero. */
public record TrafficSnapshot(
        String sessionId, long sequence, long timestamp, double intervalMs, long uptimeMs,
        Traffic traffic) {
    public record Direction(boolean available, String totalBytes, Double bytesPerSecond, Double peakBytesPerSecond) {}
    public record Traffic(Direction inbound, Direction outbound, String combinedTotalBytes) {}
}
