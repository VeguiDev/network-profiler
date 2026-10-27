package dev.networkprofiler.server;

import dev.networkprofiler.api.*;
import dev.networkprofiler.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DashboardSettingsTest {
    @TempDir Path directory;
    @Test void atomicSharedPersistenceValidationAndFailedWritesPreservePreviousValue() throws Exception {
        var path = directory.resolve("dashboard-settings.json"); var settings = new DashboardSettings(path);
        assertNull(settings.current().inboundMbps()); settings.update(new DashboardSettings.Bandwidth(100d, 12.5));
        assertEquals(settings.current(), new DashboardSettings(path).current());
        for (double n : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, 1000001})
            assertThrows(IllegalArgumentException.class, () -> new DashboardSettings.Bandwidth(n, null));
        Files.delete(path); Files.createDirectory(path); Files.writeString(path.resolve("block"), "occupied");
        assertThrows(java.io.IOException.class, () -> settings.update(new DashboardSettings.Bandwidth(200d, null)));
        assertEquals(100d, settings.current().inboundMbps());
        try (var files = Files.list(directory)) { assertFalse(files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp"))); }
    }
    @Test void httpSettingsRequireHostOriginCsrfAndStrictJsonEvenWithoutPacketEvents() throws Exception {
        var json = JsonMapper.builder().build(); var settings = new DashboardSettings(directory.resolve("settings.json"));
        NetworkPlatform platform = new NetworkPlatform() {
            public String name() { return "Test"; } public String version() { return "1"; }
            public Set<ProfilerCapability> capabilities() { return Set.of(); }
        };
        try (var profiler = new TrafficProfiler(900, true, true);
             var server = new ProfilerServer(profiler, platform, "test", ServerOptions.local(0), null, null, () -> false, settings);
             var http = HttpClient.newHttpClient()) {
            profiler.start(); server.start(); String root = "http://127.0.0.1:" + server.port();
            var get = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/settings")).build(), HttpResponse.BodyHandlers.ofString());
            String token = json.readTree(get.body()).get("csrfToken").asString();
            for (int mode = 0; mode < 3; mode++) {
                var req = HttpRequest.newBuilder(URI.create(root + "/api/v1/settings")).header("Content-Type", "application/json");
                if (mode != 0) req.header("X-MNP-CSRF", token);
                if (mode != 1) req.header("Origin", mode == 2 ? "http://evil.example" : root);
                assertEquals(403, http.send(req.POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            for (String body : List.of("{}", "null", "[", "{\"inboundMbps\":0,\"outboundMbps\":null}", "{\"inboundMbps\":\"10\",\"outboundMbps\":null}", "{\"inboundMbps\":1,\"outboundMbps\":2,\"unknown\":3}", "{\"inboundMbps\":100,\"outboundMbps\":12.5}")) {
                var response = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/settings")).header("Origin", root).header("X-MNP-CSRF", token).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(body.contains(":100") ? 200 : 400, response.statusCode(), response.body());
            }
            assertEquals(100d, new DashboardSettings(directory.resolve("settings.json")).current().inboundMbps());
            assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(root + "/settings")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
}
