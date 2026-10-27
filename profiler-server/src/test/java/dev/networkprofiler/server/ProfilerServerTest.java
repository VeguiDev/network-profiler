package dev.networkprofiler.server;

import dev.networkprofiler.api.*;
import dev.networkprofiler.core.TrafficProfiler;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfilerServerTest {
    private static final NetworkPlatform PLATFORM = new NetworkPlatform() {
        public String name() { return "Test"; }
        public String version() { return "1"; }
        public Set<ProfilerCapability> capabilities() { return Set.of(ProfilerCapability.TOTAL_TRAFFIC); }
    };
    @Test void servesRealSnapshotsHistoryStaticFilesAndHealthThenCloses() throws Exception {
        var profiler = new TrafficProfiler(900, true, true);
        var server = new ProfilerServer(profiler, PLATFORM, "test", ServerOptions.local(0));
        try (var http = HttpClient.newHttpClient()) {
            profiler.start(); profiler.collector().received(1024); profiler.collector().sent(2048); profiler.sample(); server.start();
            String root = "http://127.0.0.1:" + server.port();
            var response = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/traffic/current")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode()); assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent());
            var json = JsonMapper.builder().build(); var sample = json.readTree(response.body());
            assertEquals("1024", sample.at("/traffic/inbound/totalBytes").asString());
            assertEquals("2048", sample.at("/traffic/outbound/totalBytes").asString());
            assertEquals(profiler.current().sessionId(), sample.get("sessionId").asString());
            for (String path : List.of("/api/v1/status", "/api/v1/traffic/history", "/api/v1/health", "/")) {
                assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(root + path)).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), path);
            }
            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/missing")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            profiler.fail("test failure");
            assertEquals(503, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/health")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            server.close();
            assertThrows(Exception.class, () -> http.send(HttpRequest.newBuilder(URI.create(root + "/")).timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString()));
        } finally { server.close(); profiler.close(); }
    }
    @Test void websocketSendsSnapshotsAndRejectsForeignOrigin() throws Exception {
        var profiler = new TrafficProfiler(900, true, true);
        try (var server = new ProfilerServer(profiler, PLATFORM, "test", ServerOptions.local(0)); var http = HttpClient.newHttpClient()) {
            profiler.start(); server.start();
            URI endpoint = URI.create("ws://127.0.0.1:" + server.port() + "/api/v1/live");
            var messages = new LinkedBlockingQueue<String>();
            var ws = http.newWebSocketBuilder().buildAsync(endpoint, new WebSocket.Listener() {
                final StringBuilder buffer = new StringBuilder();
                public void onOpen(WebSocket ws) { ws.request(1); }
                public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
                    buffer.append(text); if (last) { messages.add(buffer.toString()); buffer.setLength(0); } ws.request(1); return null;
                }
            }).get(5, TimeUnit.SECONDS);
            assertTrue(Objects.requireNonNull(messages.poll(3, TimeUnit.SECONDS)).contains("traffic.snapshot"));
            profiler.collector().received(777); profiler.sample();
            assertTrue(Objects.requireNonNull(messages.poll(3, TimeUnit.SECONDS)).contains("777"));
            assertThrows(ExecutionException.class, () -> http.newWebSocketBuilder().header("Origin", "https://evil.example")
                    .buildAsync(endpoint, new WebSocket.Listener() {}).get(5, TimeUnit.SECONDS));
            ws.sendClose(1000, "done").get(3, TimeUnit.SECONDS);
        } finally { profiler.close(); }
    }
    @Test void guardRejectsRebindingAndMalformedOrigins() {
        var guard = new RequestGuard(ServerOptions.local(0));
        assertTrue(guard.hostAllowed("localhost:8765")); assertFalse(guard.hostAllowed("evil.example"));
        assertFalse(guard.hostAllowed("localhost@evil.example")); assertFalse(guard.hostAllowed(null));
        assertTrue(guard.originAllowed("localhost:8765", "http://localhost:8765"));
        assertFalse(guard.originAllowed("localhost:8765", "http://localhost:8765.evil.example"));
        assertFalse(guard.originAllowed("localhost:8765", "null"));
        assertFalse(guard.originAllowed("localhost:8765", "https://localhost:8765/path"));
    }
}
