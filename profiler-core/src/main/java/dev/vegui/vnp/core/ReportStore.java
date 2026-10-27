package dev.vegui.vnp.core;

import dev.vegui.vnp.api.PacketCaptureReport;
import java.io.IOException;
import java.util.List;

/** All methods run on the capture worker, never on a network event loop. */
public interface ReportStore {
    List<PacketCaptureReport> load() throws IOException;
    void save(PacketCaptureReport report) throws IOException;
}
