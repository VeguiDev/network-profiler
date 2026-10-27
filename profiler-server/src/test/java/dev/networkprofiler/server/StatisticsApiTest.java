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
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsApiTest {
    @TempDir Path directory;
    @Test void paginatesWithoutChangingDenominatorAndValidatesScopes() throws Exception {
        var json = JsonMapper.builder().build();
        var provider = new PacketCaptureProvider() {
            public List<PacketCaptureReport.Descriptor> descriptors() { return List.of(new PacketCaptureReport.Descriptor(0, "PING", "PLAY", TrafficDirection.INBOUND)); }
            public void start(Recorder recorder) {} public void stop() {}
        };
        NetworkPlatform platform = new NetworkPlatform() {
            public String name() { return "Test"; } public String version() { return "1"; }
            public Set<ProfilerCapability> capabilities() { return Set.of(ProfilerCapability.SERVER_ATTRIBUTION); }
        };
        try (var profiler = new TrafficProfiler(900, true, true);
             var stats = new PacketStatistics(StatisticsOptions.defaults());
             var captures = new PacketCaptures(provider, new FileReportStore(directory, CaptureOptions.defaults()), CaptureOptions.defaults());
             var server = new ProfilerServer(profiler, platform, "test", ServerOptions.local(0), captures, stats, () -> true);
             var http = HttpClient.newHttpClient()) {
            profiler.start(); server.start(); String root = "http://127.0.0.1:" + server.port();
            var alice = stats.open(); String player = UUID.randomUUID().toString(); alice.associate(player, "Alice", "lobby");
            alice.association().record(TrafficDirection.INBOUND, 150, false);
            var bob = stats.open(); bob.associate(UUID.randomUUID().toString(), "Bob", "survival");
            bob.association().record(TrafficDirection.OUTBOUND, 350, false); stats.sample();
            var page = json.readTree(http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/connections?q=Alice&pageSize=1")).build(), HttpResponse.BodyHandlers.ofString()).body());
            assertEquals(1, page.get("total").asInt()); assertEquals(1, page.get("rows").size());
            assertEquals("150", page.get("intervalInboundBytes").asString()); assertEquals("350", page.get("intervalOutboundBytes").asString());
            for (String path : List.of("/api/v1/players/" + player, "/api/v1/servers/lobby", "/api/v1/connections/" + alice.id()))
                assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(root + path)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            for (String path : List.of("/servers/lobby", "/connections/" + alice.id(), "/players/" + player))
                assertTrue(http.send(HttpRequest.newBuilder(URI.create(root + path)).build(), HttpResponse.BodyHandlers.ofString()).body().contains("Network Profiler"));
            assertEquals(400, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/servers?pageSize=201")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!captures.available() && System.nanoTime() < deadline) Thread.sleep(5);
            var index = json.readTree(http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/captures")).build(), HttpResponse.BodyHandlers.ofString()).body());
            String token = index.get("csrfToken").asString();
            for (String scope : List.of("{\"type\":\"SERVER\",\"id\":\"missing\"}", "{\"type\":\"PLAYER\",\"id\":\"bad\"}", "{\"type\":\"GLOBAL\",\"unexpected\":true}")) {
                var req = HttpRequest.newBuilder(URI.create(root + "/api/v1/captures")).header("Content-Type", "application/json").header("Origin", root).header("X-MNP-CSRF", token)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"scope\":" + scope + "}")).build();
                assertEquals(400, http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            var request = HttpRequest.newBuilder(URI.create(root + "/api/v1/captures")).header("Content-Type", "application/json").header("Origin", root).header("X-MNP-CSRF", token)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"scope\":{\"type\":\"PLAYER\",\"id\":\"" + player + "\"}}")).build();
            var started = http.send(request, HttpResponse.BodyHandlers.ofString()); assertEquals(201, started.statusCode(), started.body());
            assertEquals(new CaptureScope("PLAYER", player), captures.current().scope());
        }
    }
}
