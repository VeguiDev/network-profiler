package dev.vegui.vnp.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfilerConfigTest {
    @TempDir Path directory;
    @Test void createsSafeDefaults() throws Exception {
        var config = ProfilerConfig.load(directory);
        assertEquals(RelayBuildConfiguration.options(true), config.relay()); assertTrue(config.relay().enabled());
        assertEquals(900, config.historySeconds()); assertEquals("native", config.provider());
        assertFalse(Files.readString(directory.resolve("config.yml")).contains("public-web-url:"));
        assertFalse(Files.readString(directory.resolve("config.yml")).contains("endpoint:"));
    }
    @Test void relayIsOptOutAndUsesOnlyEmbeddedOrigins() throws Exception {
        Files.writeString(directory.resolve("config.yml"), "{}");
        assertTrue(ProfilerConfig.load(directory).relay().enabled());
        Files.writeString(directory.resolve("config.yml"), "relay:\n  enabled: false\n");
        var config = ProfilerConfig.load(directory);
        assertFalse(config.relay().enabled());
        assertEquals(RelayBuildConfiguration.options(true).endpoint(), config.relay().endpoint());
        assertEquals(RelayBuildConfiguration.options(true).publicWeb(), config.relay().publicWeb());
    }
    @Test void rejectsTyposAndUnsafeOrInvalidOptions() throws Exception {
        for (String yaml : new String[]{"relay:\n  endpoint: http://example.com", "profiler:\n  history-duration-seconds: 10000000", "traffic:\n  enabled: maybe", "http:\n  allowd-hosts: []", "http:\n  port: 8765\n  port: 1234", "!!java.lang.Runtime {}",
                "captures:\n  max-duration-seconds: 0", "captures:\n  max-reports: 1001", "relay:\n  endpoint: https://user:secret@example.com", "relay:\n  public-web-url: https://example.com/subpath",
                "relay:\n  endpoint: https://networkprofiler.vegui.dev", "relay:\n  enabled: false\n  public-web-url: https://networkprofiler.vegui.dev"}) {
            Files.writeString(directory.resolve("config.yml"), yaml);
            assertThrows(RuntimeException.class, () -> ProfilerConfig.load(directory), yaml);
        }
    }
}
