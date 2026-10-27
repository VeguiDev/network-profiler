package dev.vegui.vnp.server;

import dev.vegui.vnp.api.*;
import dev.vegui.vnp.core.*;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Local allowlisted operations. No listener, sockets, authentication or arbitrary forwarding. */
public final class ProfilerService {
    public record Result(int status, Object body) {}
    public record Status(String state, String detail, String platform, String platformVersion, String version,
                         String sessionId, long uptimeMs, Set<ProfilerCapability> capabilities) {}
    public record History(String sessionId, List<TrafficSnapshot> samples) {}
    public record CaptureIndex(boolean available, String detail, int maxDurationSeconds, List<PacketCaptureReport> reports) {}
    public record SettingsResponse(DashboardSettings.Bandwidth bandwidth, boolean persistent) {}
    public record StatisticsPage(boolean available, String detail, long timestamp, double intervalMs, boolean ready,
                                 boolean complete, String observationErrors, String untrackedBytes,
                                 String intervalInboundBytes, String intervalOutboundBytes, int total, int page,
                                 int pageSize, List<PacketStatisticsSnapshot.Row> rows) {}
    private final TrafficProfiler profiler;
    private final NetworkPlatform platform;
    private final String version;
    private final PacketCaptures captures;
    private final PacketStatistics statistics;
    private final BooleanSupplier packetSource;
    private final DashboardSettings settings;
    private final JsonMapper json = JsonMapper.builder().build();
    public ProfilerService(TrafficProfiler profiler, NetworkPlatform platform, String version, PacketCaptures captures,
                           PacketStatistics statistics, BooleanSupplier packetSource, DashboardSettings settings) {
        this.profiler = profiler; this.platform = platform; this.version = version; this.captures = captures;
        this.statistics = statistics; this.packetSource = packetSource; this.settings = settings;
    }
    public CompletableFuture<Result> request(String method, String path, String input) {
        try {
            if (path == null || path.length() > 2048 || !path.startsWith("/api/v1/") || path.contains("#")) return done(400, Map.of("error", "Invalid path"));
            var uri = URI.create(path);
            if (uri.isAbsolute() || uri.getAuthority() != null) return done(400, Map.of("error", "Invalid path"));
            var parts = uri.getPath().substring(8).split("/");
            String resource = parts[0], id = parts.length > 1 ? parts[1] : null;
            if (method.equals("GET")) {
                if (path.equals("/api/v1/status")) {
                    var s = profiler.current(); return done(200, new Status(profiler.state(), profiler.detail(), platform.name(), platform.version(), version, s.sessionId(), s.uptimeMs(), platform.capabilities()));
                }
                if (path.equals("/api/v1/health")) return done(profiler.healthy() ? 200 : 503, Map.of("healthy", profiler.healthy(), "state", profiler.state(), "detail", profiler.detail()));
                if (path.equals("/api/v1/traffic/current")) return done(200, profiler.current());
                if (path.equals("/api/v1/traffic/history")) {
                    synchronized (profiler) { return done(200, new History(profiler.current().sessionId(), profiler.history())); }
                }
                if (path.equals("/api/v1/settings")) return done(200, new SettingsResponse(settings.current(), settings.persistent()));
                if (resource.equals("captures") && parts.length <= 2) {
                    if (id == null) return done(200, new CaptureIndex(captures != null && captures.available(), captures == null ? "Packet analysis unavailable" : captures.detail(),
                            captures == null ? 0 : captures.maxDurationSeconds(), captures == null ? List.of() : captures.list().stream().map(PacketCaptureReport::summary).toList()));
                    var r = captures == null ? null : captures.find(id); return done(r == null ? 404 : 200, r == null ? Map.of("error", "Report not found") : r);
                }
                if (Set.of("servers", "connections", "players").contains(resource) && parts.length <= 2)
                    return statistics(resource.equals("servers") ? "SERVER" : resource.equals("players") ? "PLAYER" : "CONNECTION", id, query(uri));
            } else if (method.equals("POST")) {
                if (input == null || input.length() > 4096) throw new IllegalArgumentException("Invalid request body");
                var body = json.readTree(input);
                if (body == null || !body.isObject()) throw new IllegalArgumentException("Expected JSON object");
                if (path.equals("/api/v1/settings")) {
                    if (body.size() != 2 || !body.has("inboundMbps") || !body.has("outboundMbps")) throw new IllegalArgumentException("Expected inboundMbps and outboundMbps");
                    for (var key : List.of("inboundMbps", "outboundMbps")) if (!body.get(key).isNull() && !body.get(key).isNumber()) throw new IllegalArgumentException("Expected number or null");
                    var next = new DashboardSettings.Bandwidth(body.get("inboundMbps").isNull() ? null : body.get("inboundMbps").doubleValue(), body.get("outboundMbps").isNull() ? null : body.get("outboundMbps").doubleValue());
                    settings.update(next); return done(200, new SettingsResponse(next, settings.persistent()));
                }
                if (resource.equals("captures") && (parts.length == 1 || parts.length == 3 && parts[2].equals("stop"))) {
                    if (captures == null || !captures.available()) return done(503, Map.of("error", "Packet analysis unavailable"));
                    if (id != null) return captures.stop(id).thenApply(r -> new Result(200, r));
                    for (var key : body.propertyNames()) if (!Set.of("durationSeconds", "scope").contains(key)) throw new IllegalArgumentException("Unknown capture option");
                    var duration = body.get("durationSeconds");
                    if (duration != null && (!duration.isInt() || !duration.canConvertToInt())) throw new IllegalArgumentException("Invalid durationSeconds");
                    var node = body.get("scope"); CaptureScope scope = CaptureScope.GLOBAL;
                    if (node != null) {
                        if (!node.isObject() || !node.has("type") || !node.get("type").isTextual() || node.size() > 2) throw new IllegalArgumentException("Invalid scope");
                        for (var key : node.propertyNames()) if (!Set.of("type", "id").contains(key)) throw new IllegalArgumentException("Unknown scope option");
                        if (node.hasNonNull("id") && !node.get("id").isTextual()) throw new IllegalArgumentException("Invalid scope id");
                        scope = new CaptureScope(node.get("type").textValue(), node.hasNonNull("id") ? node.get("id").textValue() : null);
                    }
                    if (statistics != null) {
                        if (scope.type().equals("CONNECTION") && !statistics.containsConnection(scope.id())) throw new IllegalArgumentException("Connection is not active");
                        if (scope.type().equals("PLAYER") && !statistics.containsPlayer(scope.id())) throw new IllegalArgumentException("Player is offline or untracked");
                        if (scope.type().equals("SERVER") && !statistics.containsServer(scope.id())) throw new IllegalArgumentException("Unknown server");
                    } else if (!scope.type().equals("GLOBAL")) throw new IllegalArgumentException("Attribution unavailable");
                    return done(201, captures.start(duration == null ? captures.maxDurationSeconds() : duration.intValue(), scope));
                }
            }
            return done(404, Map.of("error", "Operation not allowed"));
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException e) { return done(400, Map.of("error", Objects.toString(e.getMessage(), "Invalid input"))); }
        catch (IllegalStateException e) { return done(409, Map.of("error", Objects.toString(e.getMessage(), "Conflict"))); }
        catch (Exception e) { return done(500, Map.of("error", "Local operation failed")); }
    }
    private static CompletableFuture<Result> done(int code, Object body) { return CompletableFuture.completedFuture(new Result(code, body)); }
    private static Map<String, String> query(URI uri) {
        var result = new HashMap<String, String>();
        if (uri.getRawQuery() != null) for (var p : uri.getRawQuery().split("&")) {
            var kv = p.split("=", 2); result.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }
    private CompletableFuture<Result> statistics(String kind, String id, Map<String, String> query) {
        boolean available = statistics != null && statistics.enabled() && packetSource.getAsBoolean();
        var snapshot = statistics == null ? new PacketStatisticsSnapshot(0, 0, false, false, "0", "0", List.of(), List.of()) : statistics.current();
        var all = kind.equals("SERVER") ? snapshot.servers() : snapshot.connections();
        String q = query.getOrDefault("q", "").toLowerCase(Locale.ROOT), serverId = query.get("serverId"), playerId = query.get("playerId");
        int page = Integer.parseInt(query.getOrDefault("page", "0")), size = Integer.parseInt(query.getOrDefault("pageSize", "50"));
        if (page < 0 || page > 100000 || size < 1 || size > 200 || q.length() > 256) throw new IllegalArgumentException("Invalid pagination or search");
        var filtered = all.stream().filter(r -> id == null || (kind.equals("PLAYER") ? id.equals(r.playerId()) : id.equals(r.id())))
                .filter(r -> serverId == null || serverId.equals(r.serverId())).filter(r -> playerId == null || playerId.equals(r.playerId()))
                .filter(r -> (r.id() + " " + r.playerName() + " " + r.playerId() + " " + r.serverId()).toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.<PacketStatisticsSnapshot.Row, BigInteger>comparing(r -> new BigInteger(r.intervalInboundBytes()).add(new BigInteger(r.intervalOutboundBytes()))).reversed().thenComparing(PacketStatisticsSnapshot.Row::id)).toList();
        if (id != null && snapshot.ready() && filtered.isEmpty()) return done(404, Map.of("error", "Entity not found or expired"));
        BigInteger in = BigInteger.ZERO, out = BigInteger.ZERO;
        for (var r : all) { in = in.add(new BigInteger(r.intervalInboundBytes())); out = out.add(new BigInteger(r.intervalOutboundBytes())); }
        return done(200, new StatisticsPage(available, !available ? "Requires healthy PacketEvents statistics" : snapshot.ready() ? "Protocol observations, not proof of backend origin" : "Collecting first interval",
                snapshot.timestamp(), snapshot.intervalMs(), snapshot.ready(), snapshot.complete(), snapshot.observationErrors(), snapshot.untrackedBytes(), in.toString(), out.toString(), filtered.size(), page, size,
                filtered.stream().skip((long) page * size).limit(size).toList()));
    }
}
