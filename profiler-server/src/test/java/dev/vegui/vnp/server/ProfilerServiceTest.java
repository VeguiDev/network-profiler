package dev.vegui.vnp.server;

import dev.vegui.vnp.api.*;
import dev.vegui.vnp.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ProfilerServiceTest {
    @TempDir Path directory;
    private static final NetworkPlatform PLATFORM = new NetworkPlatform() {
        public String name() { return "Test"; } public String version() { return "1"; }
        public Set<ProfilerCapability> capabilities() { return Set.of(ProfilerCapability.TOTAL_TRAFFIC); }
    };
    private static final class Provider implements PacketCaptureProvider {
        Recorder recorder;
        public List<PacketCaptureReport.Descriptor> descriptors() { return List.of(new PacketCaptureReport.Descriptor(0, "PING", "STATUS", TrafficDirection.INBOUND)); }
        public void start(Recorder r) { recorder = r; } public void stop() {}
    }
    @Test void allowlistedLocalOperationsShareCommandCaptureStateWithoutHostingHttp() throws Exception {
        var provider = new Provider(); var options = CaptureOptions.defaults();
        try (var profiler = new TrafficProfiler(900, true, true);
             var captures = new PacketCaptures(provider, new FileReportStore(directory, options), options)) {
            var service = new ProfilerService(profiler, PLATFORM, "test", captures, null, () -> true, DashboardSettings.memory());
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!captures.available() && System.nanoTime() < until) Thread.sleep(5);
            for (String body : List.of("", "[", "null", "[]", "{\"durationSeconds\":0}", "{\"durationSeconds\":1.5}", "{\"unknown\":2}"))
                assertEquals(400, service.request("POST", "/api/v1/captures", body).get().status(), body);
            var start = service.request("POST", "/api/v1/captures", "{\"durationSeconds\":10}").get();
            assertEquals(201, start.status()); String id = ((PacketCaptureReport) start.body()).id();
            assertEquals(id, captures.current().id()); assertEquals(409, service.request("POST", "/api/v1/captures", "{}").get().status());
            provider.recorder.record(0, 150, false, false);
            assertEquals("150", ((PacketCaptureReport) service.request("GET", "/api/v1/captures/" + id, null).get().body()).totalBytes());
            var stopped = captures.stop(id).get(3, TimeUnit.SECONDS);
            assertEquals(stopped, service.request("POST", "/api/v1/captures/" + id + "/stop", "{}").get().body());
            var next = captures.start(); assertEquals(200, service.request("POST", "/api/v1/captures/" + next.id() + "/stop", "{}").get().status());
            for (String path : List.of("/api/v1/exec", "/api/v1/captures/missing", "/api/v1/status/extra"))
                assertEquals(404, service.request("GET", path, null).get().status());
            assertEquals(400, service.request("GET", "/reports", null).get().status());
            assertEquals(200, service.request("GET", "/api/v1/status", null).get().status());
            assertEquals(404, service.request("DELETE", "/api/v1/captures", null).get().status());
        }
    }
    @Test void paginationPreservesDenominatorAndScopesAreValidated() throws Exception {
        try (var profiler = new TrafficProfiler(900, true, true); var stats = new PacketStatistics(StatisticsOptions.defaults());
             var captures = new PacketCaptures(new Provider(), new FileReportStore(directory, CaptureOptions.defaults()), CaptureOptions.defaults())) {
            var service = new ProfilerService(profiler, PLATFORM, "test", captures, stats, () -> true, DashboardSettings.memory());
            var alice = stats.open(); String player = UUID.randomUUID().toString(); alice.associate(player, "Alice", "lobby");
            alice.association().record(TrafficDirection.INBOUND, 150, false);
            var bob = stats.open(); bob.associate(UUID.randomUUID().toString(), "Bob", "survival"); bob.association().record(TrafficDirection.OUTBOUND, 350, false); stats.sample();
            var page = (ProfilerService.StatisticsPage) service.request("GET", "/api/v1/connections?q=Alice&pageSize=1", null).get().body();
            assertEquals(1, page.total()); assertEquals(1, page.rows().size()); assertEquals("150", page.intervalInboundBytes()); assertEquals("350", page.intervalOutboundBytes());
            for (String path : List.of("/api/v1/players/" + player, "/api/v1/servers/lobby", "/api/v1/connections/" + alice.id())) assertEquals(200, service.request("GET", path, null).get().status());
            assertEquals(400, service.request("GET", "/api/v1/servers?pageSize=201", null).get().status());
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); while (!captures.available() && System.nanoTime() < until) Thread.sleep(5);
            for (String scope : List.of("{\"type\":\"SERVER\",\"id\":\"missing\"}", "{\"type\":\"PLAYER\",\"id\":\"bad\"}", "{\"type\":\"GLOBAL\",\"unexpected\":true}"))
                assertEquals(400, service.request("POST", "/api/v1/captures", "{\"scope\":" + scope + "}").get().status());
            assertEquals(201, service.request("POST", "/api/v1/captures", "{\"scope\":{\"type\":\"PLAYER\",\"id\":\"" + player + "\"}}").get().status());
            assertEquals(new CaptureScope("PLAYER", player), captures.current().scope());
        }
    }
    @Test void settingsStayLocalAtomicAndValidatedWithoutPacketEvents() throws Exception {
        var path = directory.resolve("settings.json"); var settings = new DashboardSettings(path);
        try (var profiler = new TrafficProfiler(900, true, true)) {
            var service = new ProfilerService(profiler, PLATFORM, "test", null, null, () -> false, settings);
            for (String body : List.of("{}", "null", "[", "{\"inboundMbps\":0,\"outboundMbps\":null}", "{\"inboundMbps\":\"10\",\"outboundMbps\":null}", "{\"inboundMbps\":1,\"outboundMbps\":2,\"unknown\":3}"))
                assertEquals(400, service.request("POST", "/api/v1/settings", body).get().status());
            assertEquals(200, service.request("POST", "/api/v1/settings", "{\"inboundMbps\":100,\"outboundMbps\":12.5}").get().status());
            assertEquals(settings.current(), new DashboardSettings(path).current());
            Files.delete(path); Files.createDirectory(path); Files.writeString(path.resolve("block"), "occupied");
            assertEquals(500, service.request("POST", "/api/v1/settings", "{\"inboundMbps\":200,\"outboundMbps\":null}").get().status());
            assertEquals(100d, settings.current().inboundMbps());
            for (double n : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, 1000001}) assertThrows(IllegalArgumentException.class, () -> new DashboardSettings.Bandwidth(n, null));
            try (var files = Files.list(directory)) { assertFalse(files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp"))); }
        }
    }
}
