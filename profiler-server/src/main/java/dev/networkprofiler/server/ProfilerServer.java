package dev.networkprofiler.server;

import dev.networkprofiler.api.*;
import dev.networkprofiler.core.TrafficProfiler;
import dev.networkprofiler.core.PacketCaptures;
import dev.networkprofiler.core.PacketStatistics;
import java.math.BigInteger;
import java.util.function.BooleanSupplier;
import io.javalin.Javalin;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.ServiceUnavailableResponse;
import io.javalin.http.staticfiles.Location;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class ProfilerServer implements AutoCloseable {
    private final TrafficProfiler profiler;
    private final NetworkPlatform platform;
    private final String version;
    private final ServerOptions options;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<Session, LiveClient> clients = new ConcurrentHashMap<>();
    private Javalin app;
    private AutoCloseable subscription;
    private ScheduledExecutorService watchdog;
    private final PacketCaptures captures;
    private final PacketStatistics statistics;
    private final BooleanSupplier packetSourceAvailable;
    private final DashboardSettings settings;
    public record SettingsResponse(DashboardSettings.Bandwidth bandwidth, String csrfToken, boolean persistent) {}
    private final String csrfToken = newToken();
    public record CaptureIndex(boolean available, String detail, String csrfToken, int maxDurationSeconds, List<PacketCaptureReport> reports) {}
    public record StatisticsPage(boolean available, String detail, long timestamp, double intervalMs, boolean ready,
                                 boolean complete, String observationErrors, String untrackedBytes,
                                 String intervalInboundBytes, String intervalOutboundBytes, int total, int page,
                                 int pageSize, List<PacketStatisticsSnapshot.Row> rows) {}
    private static String newToken() {
        byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
    public record Status(String state, String detail, String platform, String platformVersion, String version,
                         String sessionId, long uptimeMs, Set<ProfilerCapability> capabilities) {}
    public record History(String sessionId, List<TrafficSnapshot> samples) {}
    public record LiveMessage(String type, String sessionId, long sequence, long timestamp, double intervalMs,
                              long uptimeMs, TrafficSnapshot.Traffic traffic, Integer players) {}
    public ProfilerServer(TrafficProfiler profiler, NetworkPlatform platform, String version, ServerOptions options) {
        this(profiler, platform, version, options, null);
    }
    public ProfilerServer(TrafficProfiler profiler, NetworkPlatform platform, String version, ServerOptions options, PacketCaptures captures) {
        this(profiler, platform, version, options, captures, null, () -> false);
    }
    public ProfilerServer(TrafficProfiler profiler, NetworkPlatform platform, String version, ServerOptions options,
                           PacketCaptures captures, PacketStatistics statistics, BooleanSupplier packetSourceAvailable) {
        this(profiler, platform, version, options, captures, statistics, packetSourceAvailable, DashboardSettings.memory());
    }
    public ProfilerServer(TrafficProfiler profiler, NetworkPlatform platform, String version, ServerOptions options,
                          PacketCaptures captures, PacketStatistics statistics, BooleanSupplier packetSourceAvailable, DashboardSettings settings) {
        this.profiler = profiler; this.platform = platform; this.version = version; this.options = options;
        this.captures = captures;
        this.statistics = statistics; this.packetSourceAvailable = packetSourceAvailable;
        this.settings = settings;
    }
    public synchronized void start() {
        if (app != null) return;
        var guard = new RequestGuard(options);
        app = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.http.maxRequestSize = 4096;
            config.staticFiles.add("/web", Location.CLASSPATH);
            config.jetty.modifyWebSocketServletFactory(factory -> {
                factory.setIdleTimeout(Duration.ofSeconds(30));
                factory.setMaxTextMessageSize(1024);
                factory.setMaxBinaryMessageSize(1024);
            });
            config.routes.before(ctx -> {
                if (!guard.hostAllowed(ctx.header("Host"))) throw new ForbiddenResponse("Host not allowed");
                ctx.header("X-Content-Type-Options", "nosniff");
                ctx.header("Referrer-Policy", "no-referrer");
                ctx.header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'");
                if (ctx.path().startsWith("/api/")) ctx.header("Cache-Control", "no-store");
            });
            config.routes.get("/api/v1/status", ctx -> ctx.contentType("application/json").result(json.writeValueAsString(status())));
            config.routes.get("/api/v1/traffic/current", ctx -> ctx.contentType("application/json").result(json.writeValueAsString(profiler.current())));
            config.routes.get("/api/v1/traffic/history", ctx -> {
                synchronized (profiler) {
                    ctx.contentType("application/json").result(json.writeValueAsString(new History(profiler.current().sessionId(), profiler.history())));
                }
            });
            config.routes.get("/api/v1/health", ctx -> ctx.status(profiler.healthy() ? 200 : 503)
                    .contentType("application/json").result(json.writeValueAsString(Map.of("healthy", profiler.healthy(), "state", profiler.state(), "detail", profiler.detail()))));
            config.routes.get("/api/v1/captures", ctx -> ctx.contentType("application/json").result(json.writeValueAsString(
                    new CaptureIndex(captures != null && captures.available(), captures == null ? "Packet analysis unavailable" : captures.detail(), csrfToken, captures == null ? 0 : captures.maxDurationSeconds(),
                            captures == null ? List.of() : captures.list().stream().map(PacketCaptureReport::summary).toList()))));
            config.routes.get("/api/v1/servers", ctx -> statisticsResponse(ctx, "SERVER", null));
            config.routes.get("/api/v1/servers/{id}", ctx -> statisticsResponse(ctx, "SERVER", ctx.pathParam("id")));
            config.routes.get("/api/v1/connections", ctx -> statisticsResponse(ctx, "CONNECTION", null));
            config.routes.get("/api/v1/connections/{id}", ctx -> statisticsResponse(ctx, "CONNECTION", ctx.pathParam("id")));
            config.routes.get("/api/v1/players/{id}", ctx -> statisticsResponse(ctx, "PLAYER", ctx.pathParam("id")));
            config.routes.get("/api/v1/captures/{id}", ctx -> {
                var report = captures == null ? null : captures.find(ctx.pathParam("id"));
                if (report == null) throw new io.javalin.http.NotFoundResponse("Report not found");
                ctx.contentType("application/json").result(json.writeValueAsString(report));
            });
            config.routes.before("/api/v1/*", ctx -> {
                if (!ctx.method().name().equals("POST") || !(ctx.path().startsWith("/api/v1/captures") || ctx.path().equals("/api/v1/settings"))) return;
                String token = ctx.header("X-MNP-CSRF"), origin = ctx.header("Origin");
                if (origin == null || !guard.originAllowed(ctx.header("Host"), origin) || token == null
                        || !java.security.MessageDigest.isEqual(csrfToken.getBytes(java.nio.charset.StandardCharsets.US_ASCII), token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                    throw new ForbiddenResponse("Origin and CSRF token required");
                if (ctx.contentType() == null || !ctx.contentType().split(";", 2)[0].equalsIgnoreCase("application/json"))
                    throw new io.javalin.http.BadRequestResponse("Expected application/json");
                if (ctx.path().startsWith("/api/v1/captures") && (captures == null || !captures.available())) throw new ServiceUnavailableResponse("Packet analysis unavailable");
            });
            config.routes.get("/api/v1/settings", ctx -> ctx.contentType("application/json").result(json.writeValueAsString(
                    new SettingsResponse(settings.current(), csrfToken, settings.persistent()))));
            config.routes.post("/api/v1/settings", ctx -> {
                try {
                    var body = json.readTree(ctx.body());
                    if (body == null || !body.isObject() || body.size() != 2 || !body.has("inboundMbps") || !body.has("outboundMbps"))
                        throw new IllegalArgumentException("Expected inboundMbps and outboundMbps");
                    for (String key : List.of("inboundMbps", "outboundMbps"))
                        if (!body.get(key).isNull() && !body.get(key).isNumber()) throw new IllegalArgumentException("Bandwidth must be a number or null");
                    var next = new DashboardSettings.Bandwidth(body.get("inboundMbps").isNull() ? null : body.get("inboundMbps").doubleValue(),
                            body.get("outboundMbps").isNull() ? null : body.get("outboundMbps").doubleValue());
                    settings.update(next);
                    ctx.contentType("application/json").result(json.writeValueAsString(new SettingsResponse(next, csrfToken, settings.persistent())));
                } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) {
                    throw new io.javalin.http.BadRequestResponse(invalid.getMessage());
                }
            });
            config.routes.post("/api/v1/captures", ctx -> {
                try {
                    tools.jackson.databind.JsonNode body;
                    try { body = json.readTree(ctx.body()); }
                    catch (tools.jackson.core.JacksonException invalidJson) { throw new IllegalArgumentException("Invalid JSON body"); }
                    if (body == null || !body.isObject() || body.size() > 2)
                        throw new IllegalArgumentException("Expected optional durationSeconds and scope");
                    for (String key : body.propertyNames()) if (!Set.of("durationSeconds", "scope").contains(key))
                        throw new IllegalArgumentException("Unknown capture option");
                    var duration = body.get("durationSeconds");
                    if (duration != null && (!duration.isInt() || !duration.canConvertToInt())) throw new IllegalArgumentException("Invalid durationSeconds");
                    var scope = parseScope(body.get("scope"));
                    if (statistics != null) {
                        if (scope.type().equals("CONNECTION") && !statistics.containsConnection(scope.id())) throw new IllegalArgumentException("Connection is not active");
                        if (scope.type().equals("PLAYER") && !statistics.containsPlayer(scope.id())) throw new IllegalArgumentException("Player is not tracked or is offline");
                        if (scope.type().equals("SERVER") && !statistics.containsServer(scope.id())) throw new IllegalArgumentException("Unknown server");
                    } else if (!scope.type().equals("GLOBAL")) throw new IllegalArgumentException("Attribution is unavailable");
                    var report = captures.start(duration == null ? captures.maxDurationSeconds() : duration.intValue(), scope);
                    ctx.status(201).contentType("application/json").result(json.writeValueAsString(report));
                } catch (IllegalStateException conflict) { throw new io.javalin.http.ConflictResponse(conflict.getMessage()); }
                catch (IllegalArgumentException invalid) { throw new io.javalin.http.BadRequestResponse(invalid.getMessage()); }
            });
            config.routes.post("/api/v1/captures/{id}/stop", ctx -> {
                try {
                    var future = captures.stop(ctx.pathParam("id"));
                    ctx.future(() -> future.thenAccept(report -> ctx.contentType("application/json").result(json.writeValueAsString(report))));
                } catch (IllegalStateException conflict) { throw new io.javalin.http.ConflictResponse(conflict.getMessage()); }
            });
            for (String page : List.of("/settings", "/reports", "/reports/{id}", "/servers", "/servers/{id}", "/connections", "/connections/{id}", "/players/{id}")) config.routes.get(page, ctx ->
                    ctx.contentType("text/html").result(Objects.requireNonNull(ProfilerServer.class.getResourceAsStream("/web/index.html"))));
            config.routes.wsBeforeUpgrade("/api/v1/live", ctx -> {
                if (!guard.hostAllowed(ctx.header("Host")) || !guard.originAllowed(ctx.header("Host"), ctx.header("Origin")))
                    throw new ForbiddenResponse("Origin or Host not allowed");
                if (clients.size() >= options.maxClients()) throw new ServiceUnavailableResponse("Live client limit reached");
            });
            config.routes.ws("/api/v1/live", ws -> {
                ws.onConnect(ctx -> {
                    synchronized (clients) {
                        if (clients.size() >= options.maxClients()) { ctx.session.close(1013, "Client limit", Callback.NOOP); return; }
                        var client = new LiveClient((message, done) -> ctx.session.sendText(message,
                                Callback.from(() -> done.accept(null), done)), () -> ctx.session.close(1001, "Stream closed", Callback.NOOP));
                        clients.put(ctx.session, client);
                        client.offer(encode(profiler.current()));
                    }
                });
                ws.onMessage(ctx -> ctx.session.close(1008, "Read-only stream", Callback.NOOP));
                ws.onBinaryMessage(ctx -> ctx.session.close(1008, "Read-only stream", Callback.NOOP));
                ws.onClose(ctx -> { var client = clients.remove(ctx.session); if (client != null) client.close(); });
                ws.onError(ctx -> { var client = clients.remove(ctx.session); if (client != null) client.close(); });
            });
            config.routes.error(404, ctx -> {
                if (ctx.path().startsWith("/api/")) ctx.contentType("application/json").result("{\"error\":\"not_found\"}");
            });
        });
        try {
            app.start(options.host(), options.port());
            subscription = profiler.subscribe(sample -> {
                String message = encode(sample);
                clients.values().forEach(client -> client.offer(message));
            });
            watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
                var thread = new Thread(r, "network-profiler-live-watchdog"); thread.setDaemon(true); return thread;
            });
            watchdog.scheduleAtFixedRate(() -> clients.values().forEach(c -> c.checkTimeout(System.nanoTime())), 1, 1, TimeUnit.SECONDS);
        } catch (RuntimeException failure) { close(); throw failure; }
    }
    private String encode(TrafficSnapshot snapshot) {
        return json.writeValueAsString(new LiveMessage("traffic.snapshot", snapshot.sessionId(), snapshot.sequence(),
                snapshot.timestamp(), snapshot.intervalMs(), snapshot.uptimeMs(), snapshot.traffic(), snapshot.players()));
    }
    private CaptureScope parseScope(tools.jackson.databind.JsonNode node) {
        if (node == null) return CaptureScope.GLOBAL;
        if (!node.isObject() || !node.has("type") || !node.get("type").isTextual() || node.size() > 2
                || node.has("id") && !node.get("id").isNull() && !node.get("id").isTextual())
            throw new IllegalArgumentException("Invalid scope");
        for (String key : node.propertyNames()) if (!Set.of("type", "id").contains(key)) throw new IllegalArgumentException("Unknown scope option");
        return new CaptureScope(node.get("type").textValue(), node.hasNonNull("id") ? node.get("id").textValue() : null);
    }
    private void statisticsResponse(io.javalin.http.Context ctx, String kind, String id) {
        boolean available = statistics != null && statistics.enabled() && packetSourceAvailable.getAsBoolean();
        var snapshot = statistics == null ? new PacketStatisticsSnapshot(0, 0, false, false, "0", "0", List.of(), List.of()) : statistics.current();
        var all = kind.equals("SERVER") ? snapshot.servers() : snapshot.connections();
        String q = Objects.toString(ctx.queryParam("q"), "").toLowerCase(Locale.ROOT);
        String serverId = ctx.queryParam("serverId"), playerId = ctx.queryParam("playerId");
        int page, size;
        try {
            page = Integer.parseInt(Objects.toString(ctx.queryParam("page"), "0"));
            size = Integer.parseInt(Objects.toString(ctx.queryParam("pageSize"), "50"));
            if (page < 0 || page > 100000 || size < 1 || size > 200 || q.length() > 256) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw new io.javalin.http.BadRequestResponse("Invalid pagination or search"); }
        var filtered = all.stream().filter(r -> id == null || (kind.equals("PLAYER") ? id.equals(r.playerId()) : id.equals(r.id())))
                .filter(r -> serverId == null || serverId.equals(r.serverId()))
                .filter(r -> playerId == null || playerId.equals(r.playerId()))
                .filter(r -> (r.id() + " " + r.playerName() + " " + r.playerId() + " " + r.serverId()).toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.<PacketStatisticsSnapshot.Row, BigInteger>comparing(r -> new BigInteger(r.intervalInboundBytes()).add(new BigInteger(r.intervalOutboundBytes()))).reversed().thenComparing(PacketStatisticsSnapshot.Row::id)).toList();
        if (id != null && snapshot.ready() && filtered.isEmpty()) throw new io.javalin.http.NotFoundResponse("Entity not found or no longer retained");
        BigInteger in = BigInteger.ZERO, out = BigInteger.ZERO;
        for (var r : all) { in = in.add(new BigInteger(r.intervalInboundBytes())); out = out.add(new BigInteger(r.intervalOutboundBytes())); }
        var result = new StatisticsPage(available, !available ? "Requires enabled statistics and healthy external PacketEvents" : snapshot.ready()
                ? "Protocol observations, server association is not proof of backend origin" : "Collecting the first " + statistics.intervalSeconds() + " second interval",
                snapshot.timestamp(), snapshot.intervalMs(), snapshot.ready(), snapshot.complete(), snapshot.observationErrors(), snapshot.untrackedBytes(),
                in.toString(), out.toString(), filtered.size(), page, size, filtered.stream().skip((long) page * size).limit(size).toList());
        ctx.contentType("application/json").result(json.writeValueAsString(result));
    }
    public Status status() {
        var sample = profiler.current();
        return new Status(profiler.state(), profiler.detail(), platform.name(), platform.version(), version,
                sample.sessionId(), sample.uptimeMs(), platform.capabilities());
    }
    public int port() { if (app == null) throw new IllegalStateException("Not started"); return app.port(); }
    @Override public synchronized void close() {
        if (subscription != null) { try { subscription.close(); } catch (Exception ignored) {} subscription = null; }
        if (watchdog != null) { watchdog.shutdownNow(); watchdog = null; }
        clients.values().forEach(LiveClient::close); clients.clear();
        if (app != null) { app.stop(); app = null; }
    }
}
