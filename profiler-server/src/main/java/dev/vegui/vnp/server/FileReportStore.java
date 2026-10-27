package dev.vegui.vnp.server;

import dev.vegui.vnp.api.PacketCaptureReport;
import dev.vegui.vnp.core.*;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Own UUID-named JSON files only; bounded size/count, atomic replacement, no database. */
public final class FileReportStore implements ReportStore {
    private static final long MAX_FILE_BYTES = 32 * 1024 * 1024;
    private final Path directory;
    private final CaptureOptions options;
    private final JsonMapper json = JsonMapper.builder().build();
    public FileReportStore(Path directory, CaptureOptions options) { this.directory = directory; this.options = options; }
    private boolean owned(Path path) {
        return path.getFileName().toString().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json")
                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }
    private List<Path> prune() throws IOException {
        Files.createDirectories(directory);
        List<Path> paths;
        try (var files = Files.list(directory)) { paths = new ArrayList<>(files.filter(this::owned).toList()); }
        paths.sort(Comparator.comparingLong(this::modified).reversed());
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(options.retentionDays());
        var keep = new ArrayList<Path>();
        for (var path : paths) {
            if (keep.size() >= options.maxReports() || modified(path) < cutoff) Files.delete(path);
            else keep.add(path);
        }
        return keep;
    }
    private long modified(Path path) {
        try { return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(); }
        catch (IOException failure) { return 0; }
    }
    @Override public List<PacketCaptureReport> load() throws IOException {
        var result = new ArrayList<PacketCaptureReport>();
        for (var path : prune()) {
            try {
                if (Files.size(path) > MAX_FILE_BYTES) throw new IOException("Report exceeds size limit");
                var report = json.readValue(Files.readString(path), PacketCaptureReport.class);
                if ((report.schemaVersion() < 1 || report.schemaVersion() > 3) || !path.getFileName().toString().equals(report.id() + ".json")
                        || report.packets().size() > 4096 || report.attributions().size() > 20000
                        || report.attributions().stream().mapToInt(a -> a.packets().size()).sum() > 20000)
                    throw new IOException("Invalid report schema");
                if (report.timeline() != null && (report.timeline().samples().size() > 86402
                        || report.timeline().samples().stream().mapToLong(s -> s.dimensions().size() + (s.population() == null ? 0 : s.population().entries())).sum() > 20000))
                    throw new IOException("Report timeline exceeds limits");
                if (report.state().equals("RUNNING")) {
                    report = new PacketCaptureReport(report.schemaVersion(), report.id(), "INTERRUPTED", report.startedAt(),
                            report.startedAt() + report.durationMs(), report.durationMs(),
                            "Recovered partial checkpoint after unclean shutdown. Bytes after the last checkpoint are missing; capture was not resumed.",
                            report.inboundBytes(), report.outboundBytes(), report.totalBytes(), report.packetCount(),
                             report.cancelledPackets(), report.modifiedPackets(), report.observationErrors(), report.packets(),
                              report.scope(), report.attributionComplete(), report.attributions(), report.timeline());
                    save(report);
                }
                result.add(report);
            } catch (Exception failure) {
                LoggerFactory.getLogger(FileReportStore.class).warn("Cannot read saved report {}: {}", path, failure.getMessage());
            }
        }
        return List.copyOf(result);
    }
    @Override public void save(PacketCaptureReport report) throws IOException {
        UUID.fromString(report.id()); Files.createDirectories(directory);
        byte[] bytes = json.writeValueAsBytes(report);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("Report exceeds size limit");
        Path temporary = Files.createTempFile(directory, ".report-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Path destination = directory.resolve(report.id() + ".json");
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
            prune();
        } finally { Files.deleteIfExists(temporary); }
    }
}
