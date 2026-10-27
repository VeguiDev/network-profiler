package dev.vegui.vnp.relay;

import dev.vegui.vnp.core.TrafficProfiler;
import dev.vegui.vnp.server.*;
import dev.vegui.vnp.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RelayIdentityTest {
    @TempDir Path directory;
    @Test void persistsPrivateIdentityWithoutExposingTokenAndRejectsReplacementOnCorruption() throws Exception {
        var path = directory.resolve("relay-identity.json");
        var id = new RelayIdentity("https://mpn.example.com", UUID.randomUUID().toString(), "ab".repeat(32));
        assertNull(RelayIdentity.load(path, id.endpoint())); id.save(path);
        assertEquals(id, RelayIdentity.load(path, id.endpoint())); assertFalse(id.toString().contains(id.serverToken()));
        assertThrows(java.io.IOException.class, () -> RelayIdentity.load(path, "https://other.example.com"));
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(path)));
        Files.writeString(path, "corrupt"); assertThrows(java.io.IOException.class, () -> RelayIdentity.load(path, id.endpoint()));
        assertEquals("corrupt", Files.readString(path));
    }
    @Test void rejectsInsecureOrNonOriginEndpoints() {
        for (String endpoint : List.of("http://example.com", "https://example.com/path", "https://user:pass@example.com", "https://example.com?q=x", "file://localhost"))
            assertThrows(IllegalArgumentException.class, () -> new RelayOptions(true, URI.create(endpoint), URI.create("https://web.example.com")));
        assertEquals("http://127.0.0.1:3000", new RelayOptions(true, URI.create("http://127.0.0.1:3000"), URI.create("https://web.example.com")).origin());
    }
    @Test void invalidIdentityDoesNotDisableLocalCollectionOrSilentlyRegisterAgain() throws Exception {
        var path = directory.resolve("relay-identity.json"); Files.writeString(path, "corrupt");
        var notices = new java.util.concurrent.CopyOnWriteArrayList<String>();
        NetworkPlatform platform = new NetworkPlatform() {
            public String name() { return "Test"; } public String version() { return "1"; }
            public Set<ProfilerCapability> capabilities() { return Set.of(); }
        };
        try (var profiler = new TrafficProfiler(900, true, true);
             var relay = new RelayClient(new RelayOptions(true, URI.create("http://127.0.0.1:1"), URI.create("https://web.example.com")), path, profiler,
                     new ProfilerService(profiler, platform, "test", null, null, () -> false, DashboardSettings.memory()), notices::add)) {
            relay.start();
            long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (notices.isEmpty() && System.nanoTime() < end) Thread.sleep(10);
            assertFalse(notices.isEmpty()); assertTrue(relay.status().contains("Invalid local relay identity"));
            profiler.collector().received(123); assertEquals("123", profiler.sample().traffic().inbound().totalBytes());
            assertTrue(relay.dashboard().isCompletedExceptionally()); assertEquals("corrupt", Files.readString(path));
        }
    }
}
