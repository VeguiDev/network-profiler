package dev.vegui.vnp.server;

import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.*;

/** Shared visual references only. No traffic throttling or mutation of the plugin YAML. */
public final class DashboardSettings {
    public record Bandwidth(Double inboundMbps, Double outboundMbps) {
        public Bandwidth { validate(inboundMbps); validate(outboundMbps); }
        private static void validate(Double value) {
            if (value != null && (!Double.isFinite(value) || value <= 0 || value > 1_000_000))
                throw new IllegalArgumentException("Bandwidth must be null (disabled) or 0 < Mbps <= 1000000");
        }
    }
    private final Path path;
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile Bandwidth value = new Bandwidth(null, null);
    public DashboardSettings(Path path) throws IOException {
        this.path = path;
        if (path != null && Files.exists(path)) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 4096) throw new IOException("Invalid dashboard settings file");
            try {
                value = json.readValue(Files.readString(path), Bandwidth.class);
                if (value == null) throw new IllegalArgumentException("Settings cannot be null");
            }
            catch (RuntimeException invalid) { throw new IOException("Cannot load dashboard settings", invalid); }
        }
    }
    public static DashboardSettings memory() { try { return new DashboardSettings(null); } catch (IOException impossible) { throw new AssertionError(impossible); } }
    public Bandwidth current() { return value; }
    public boolean persistent() { return path != null; }
    public synchronized Bandwidth update(Bandwidth next) throws IOException {
        if (path != null) {
            Files.createDirectories(path.toAbsolutePath().getParent());
            var temporary = Files.createTempFile(path.toAbsolutePath().getParent(), ".dashboard-", ".tmp");
            try {
                Files.writeString(temporary, json.writeValueAsString(next));
                try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
        }
        value = next; return next;
    }
}
