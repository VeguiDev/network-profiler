package dev.networkprofiler.server;

import dev.networkprofiler.api.*;
import dev.networkprofiler.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CaptureApiTest {
    @TempDir Path directory;
    private static final NetworkPlatform PLATFORM = new NetworkPlatform() {
        public String name() { return "Test"; } public String version() { return "1"; }
        public Set<ProfilerCapability> capabilities() { return Set.of(ProfilerCapability.TOTAL_TRAFFIC, ProfilerCapability.PACKET_TRAFFIC); }
    };
    private static final class Provider implements PacketCaptureProvider {
        Recorder recorder;
        public List<PacketCaptureReport.Descriptor> descriptors() { return List.of(new PacketCaptureReport.Descriptor(0, "PING", "STATUS", TrafficDirection.INBOUND)); }
        public void start(Recorder recorder) { this.recorder = recorder; } public void stop() { }
    }
    private HttpResponse<String> send(HttpClient http, String root, String path, String body, String token, String origin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(root + path));
        if (body != null) request.POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        if (token != null) request.header("X-MNP-CSRF", token);
        if (origin != null) request.header("Origin", origin);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void uiAndCommandShareCaptureWithCsrfOriginAndIdempotentStop() throws Exception {
        var options = CaptureOptions.defaults(); var provider = new Provider(); var json = JsonMapper.builder().build();
        try (var profiler = new TrafficProfiler(900, true, true);
             var captures = new PacketCaptures(provider, new FileReportStore(directory, options), options);
             var server = new ProfilerServer(profiler, PLATFORM, "test", ServerOptions.local(0), captures);
             var http = HttpClient.newHttpClient()) {
            profiler.start(); server.start(); String root = "http://127.0.0.1:" + server.port();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!captures.available() && System.nanoTime() < until) Thread.sleep(5);
            assertTrue(captures.available());
            var index = json.readTree(send(http, root, "/api/v1/captures", null, null, null).body());
            String token = index.get("csrfToken").asString();
            assertEquals(403, send(http, root, "/api/v1/captures", "{}", null, root).statusCode());
            assertEquals(403, send(http, root, "/api/v1/captures", "{}", token, null).statusCode());
            assertEquals(403, send(http, root, "/api/v1/captures", "{}", token, "http://evil.example").statusCode());
            for (String invalid : List.of("", "[", "null", "[]", "{\"durationSeconds\":0}", "{\"durationSeconds\":1.5}", "{\"unknown\":2}"))
                assertEquals(400, send(http, root, "/api/v1/captures", invalid, token, root).statusCode(), invalid);
            var start = send(http, root, "/api/v1/captures", "{\"durationSeconds\":10}", token, root);
            assertEquals(201, start.statusCode(), start.body());
            String id = json.readTree(start.body()).get("id").asString();
            assertEquals(id, captures.current().id()); // Exactly the service used by /mnp.
            assertThrows(IllegalStateException.class, captures::start);
            assertEquals(409, send(http, root, "/api/v1/captures", "{}", token, root).statusCode());
            provider.recorder.record(0, 150, false, false);
            assertEquals("150", json.readTree(send(http, root, "/api/v1/captures/" + id, null, null, null).body()).get("totalBytes").asString());
            assertEquals(403, send(http, root, "/api/v1/captures/" + id + "/stop", "{}", null, root).statusCode());
            var stopped = captures.stop(id).get(3, TimeUnit.SECONDS); // Command stops a UI-started capture.
            var finalResponse = send(http, root, "/api/v1/captures/" + id + "/stop", "{}", token, root);
            assertEquals(200, finalResponse.statusCode()); assertEquals(json.writeValueAsString(stopped), finalResponse.body());
            var commandStarted = captures.start();
            assertEquals(200, send(http, root, "/api/v1/captures/" + commandStarted.id() + "/stop", "{}", token, root).statusCode());
            for (String path : List.of("/reports", "/reports/" + id)) assertTrue(send(http, root, path, null, null, null).body().contains("Network Profiler"));
            assertEquals(404, send(http, root, "/api/v1/captures/missing", null, null, null).statusCode());
            assertTrue(json.readTree(send(http, root, "/api/v1/status", null, null, null).body()).get("capabilities").toString().contains("PACKET_TRAFFIC"));
        }
    }
}
