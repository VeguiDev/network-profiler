package dev.networkprofiler.server;

import dev.networkprofiler.api.*;
import dev.networkprofiler.core.TrafficProfiler;
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
    public record Status(String state, String detail, String platform, String platformVersion, String version,
                         String sessionId, long uptimeMs, Set<ProfilerCapability> capabilities) {}
    public record History(String sessionId, List<TrafficSnapshot> samples) {}
    public record LiveMessage(String type, String sessionId, long sequence, long timestamp, double intervalMs,
                              long uptimeMs, TrafficSnapshot.Traffic traffic) {}
    public ProfilerServer(TrafficProfiler profiler, NetworkPlatform platform, String version, ServerOptions options) {
        this.profiler = profiler; this.platform = platform; this.version = version; this.options = options;
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
                snapshot.timestamp(), snapshot.intervalMs(), snapshot.uptimeMs(), snapshot.traffic()));
    }
    public Status status() {
        var sample = profiler.current();
        return new Status(profiler.state(), profiler.detail(), platform.name(), platform.version(), version,
                sample.sessionId(), sample.uptimeMs(), profiler.capabilities());
    }
    public int port() { if (app == null) throw new IllegalStateException("Not started"); return app.port(); }
    @Override public synchronized void close() {
        if (subscription != null) { try { subscription.close(); } catch (Exception ignored) {} subscription = null; }
        if (watchdog != null) { watchdog.shutdownNow(); watchdog = null; }
        clients.values().forEach(LiveClient::close); clients.clear();
        if (app != null) { app.stop(); app = null; }
    }
}
