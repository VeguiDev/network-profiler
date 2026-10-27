package dev.vegui.vnp.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfilerConfigTest {
    @TempDir Path directory;
    @Test void createsSafeDefaults() throws Exception {
        var config = ProfilerConfig.load(directory);
        assertEquals("https://vnp.vegui.dev", config.relay().origin()); assertTrue(config.relay().enabled());
        assertEquals(900, config.historySeconds()); assertEquals("native", config.provider());
    }
    @Test void rejectsTyposAndUnsafeOrInvalidOptions() throws Exception {
        for (String yaml : new String[]{"relay:\n  endpoint: http://example.com", "profiler:\n  history-duration-seconds: 10000000", "traffic:\n  enabled: maybe", "http:\n  allowd-hosts: []", "http:\n  port: 8765\n  port: 1234", "!!java.lang.Runtime {}",
                "captures:\n  max-duration-seconds: 0", "captures:\n  max-reports: 1001", "relay:\n  endpoint: https://user:secret@example.com", "relay:\n  public-web-url: https://example.com/subpath"}) {
            Files.writeString(directory.resolve("config.yml"), yaml);
            assertThrows(RuntimeException.class, () -> ProfilerConfig.load(directory), yaml);
        }
    }
}
