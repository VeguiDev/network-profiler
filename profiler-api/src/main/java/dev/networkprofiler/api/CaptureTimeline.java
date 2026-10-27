package dev.networkprofiler.api;

import java.util.List;

/** Sparse per-context interval deltas. Missing entries mean zero only when sample.complete is true. */
public record CaptureTimeline(boolean complete, List<Sample> samples) {
    public CaptureTimeline { samples = List.copyOf(samples); }
    public record Delta(PacketContext context, String inboundBytes, String outboundBytes) {}
    public record Sample(long timestamp, double elapsedMs, double intervalMs, String inboundBytes,
                         String outboundBytes, boolean complete, List<Delta> dimensions, PlayerPopulation population) {
        public Sample { dimensions = List.copyOf(dimensions); }
    }
}
