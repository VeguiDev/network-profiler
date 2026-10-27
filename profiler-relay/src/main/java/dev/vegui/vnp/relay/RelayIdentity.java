package dev.vegui.vnp.relay;

import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/** Private credential file: never included in configuration, URLs, logs or dashboard responses. */
public record RelayIdentity(String endpoint, String serverUuid, String serverToken) {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public RelayIdentity {
        UUID.fromString(serverUuid);
        if (serverToken == null || !serverToken.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid relay credentials");
    }
    public static RelayIdentity load(Path file, String endpoint) throws IOException {
        if (!Files.exists(file)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4096) throw new IOException("Invalid relay identity file");
        try {
            var identity = JSON.readValue(Files.readString(file), RelayIdentity.class);
            if (identity == null || !endpoint.equals(identity.endpoint())) throw new IOException("Relay endpoint changed; deliberately move the old identity file before registering elsewhere");
            return identity;
        } catch (RuntimeException e) { throw new IOException("Cannot read relay identity; refusing silent re-registration", e); }
    }
    public void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        var temp = Files.createTempFile(file.toAbsolutePath().getParent(), ".relay-", ".tmp");
        try {
            try { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { }
            Files.writeString(temp, JSON.writeValueAsString(this));
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, file); }
        } finally { Files.deleteIfExists(temp); }
    }
    @Override public String toString() { return "RelayIdentity[serverUuid=" + serverUuid + ", credentials=REDACTED]"; }
}
