package dev.vegui.vnp.api;

import java.util.List;

/** Protocol bytes observed at PacketEvents, not compressed transport bytes or confirmed writes. */
public record PacketCaptureReport(int schemaVersion, String id, String state, long startedAt, Long endedAt,
                                  long durationMs, String detail, String inboundBytes, String outboundBytes,
                                  String totalBytes, String packetCount, String cancelledPackets,
                                  String modifiedPackets, String observationErrors, List<Row> packets,
                                  CaptureScope scope, boolean attributionComplete, List<Attribution> attributions,
                                  CaptureTimeline timeline) {
    public PacketCaptureReport {
        packets = List.copyOf(packets);
        scope = scope == null ? CaptureScope.GLOBAL : scope;
        attributions = attributions == null ? List.of() : List.copyOf(attributions);
    }
    public PacketCaptureReport(int schemaVersion, String id, String state, long startedAt, Long endedAt,
                               long durationMs, String detail, String inboundBytes, String outboundBytes,
                               String totalBytes, String packetCount, String cancelledPackets, String modifiedPackets,
                               String observationErrors, List<Row> packets, CaptureScope scope, boolean attributionComplete,
                               List<Attribution> attributions) {
        this(schemaVersion, id, state, startedAt, endedAt, durationMs, detail, inboundBytes, outboundBytes, totalBytes,
                packetCount, cancelledPackets, modifiedPackets, observationErrors, packets, scope, attributionComplete, attributions, null);
    }
    /** Source compatibility and schema-1 loading; old reports have no attribution. */
    public PacketCaptureReport(int schemaVersion, String id, String state, long startedAt, Long endedAt,
                               long durationMs, String detail, String inboundBytes, String outboundBytes,
                               String totalBytes, String packetCount, String cancelledPackets, String modifiedPackets,
                               String observationErrors, List<Row> packets) {
        this(schemaVersion, id, state, startedAt, endedAt, durationMs, detail, inboundBytes, outboundBytes, totalBytes,
                packetCount, cancelledPackets, modifiedPackets, observationErrors, packets, CaptureScope.GLOBAL, false, List.of());
    }
    public PacketCaptureReport summary() {
        return new PacketCaptureReport(schemaVersion, id, state, startedAt, endedAt, durationMs, detail, inboundBytes,
                outboundBytes, totalBytes, packetCount, cancelledPackets, modifiedPackets, observationErrors,
                List.of(), scope, attributionComplete, List.of(), null);
    }
    public record Attribution(PacketContext context, List<Row> packets) {
        public Attribution { packets = List.copyOf(packets); }
    }
    public record Descriptor(int index, String name, String protocolState, TrafficDirection direction) {}
    public record Row(String name, String protocolState, TrafficDirection direction, String totalBytes,
                      String packetCount, String cancelledPackets, String modifiedPackets) {}
}
