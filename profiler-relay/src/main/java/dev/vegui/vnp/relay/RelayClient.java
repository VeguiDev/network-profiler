package dev.vegui.vnp.relay;

import dev.vegui.vnp.core.TrafficProfiler;
import dev.vegui.vnp.server.ProfilerService;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Outgoing-only authenticated connection. Local measurements never depend on relay availability. */
public final class RelayClient implements AutoCloseable {
    private final RelayOptions options;
    private final Path identityFile;
    private final TrafficProfiler profiler;
    private final ProfilerService service;
    private final Consumer<String> notice;
    private final JsonMapper json = JsonMapper.builder().build();
    // Avoid clear-text h2c upgrade negotiation: the Node adapter and WebSocket handshake use HTTP/1.1.
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "vnp-relay"); t.setDaemon(true); return t; });
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), r -> { var t = new Thread(r, "vnp-relay-requests"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, CompletableFuture<JsonNode>> commands = new ConcurrentHashMap<>();
    private final Set<String> seen = new LinkedHashSet<>();
    private final String bootId = UUID.randomUUID().toString();
    private volatile RelayIdentity identity;
    private volatile WebSocket socket;
    private volatile boolean closed, connecting, wanted, subscribed, fatal;
    private volatile String status = "Registering";
    private volatile long received;
    private long nextAttempt;
    private int attempts;
    private volatile int queued;
    private CompletableFuture<Void> writes = CompletableFuture.completedFuture(null);
    private final AutoCloseable subscription;
    public RelayClient(RelayOptions options, Path identityFile, TrafficProfiler profiler, ProfilerService service, Consumer<String> notice) {
        this.options = options; this.identityFile = identityFile; this.profiler = profiler; this.service = service; this.notice = notice;
        subscription = profiler.subscribe(snapshot -> {
            if (!subscribed || socket == null || queued > 8) return;
            var node = json.valueToTree(snapshot); ((tools.jackson.databind.node.ObjectNode) node).put("type", "traffic.snapshot");
            send(node);
        });
    }
    public void start() { if (options.enabled()) control.scheduleWithFixedDelay(this::tick, 0, 5, TimeUnit.SECONDS); }
    public String status() { return status; }
    private void tick() {
        if (closed || fatal) return;
        try {
            if (identity == null) {
                identity = RelayIdentity.load(identityFile, options.origin());
                if (identity == null) {
                    if (System.nanoTime() < nextAttempt) return;
                    var response = http.send(HttpRequest.newBuilder(URI.create(options.origin() + "/api/v1/server")).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofInputStream());
                    try (var body = response.body()) {
                        if (response.statusCode() != 201) throw new IllegalStateException("Registration unavailable");
                        byte[] bytes = body.readNBytes(4097); if (bytes.length > 4096) throw new IllegalStateException("Registration response too large");
                        var data = json.readTree(bytes);
                        var created = new RelayIdentity(options.origin(), data.get("serverUuid").asString(), data.get("serverToken").asString());
                        created.save(identityFile); identity = created;
                    }
                }
                status = "Registered; use /vnp dashboard"; attempts = 0;
            }
            if (!wanted) return;
            var ws = socket;
            if (ws != null) {
                if (System.nanoTime() - received > TimeUnit.SECONDS.toNanos(45)) { disconnected(ws); ws.abort(); return; }
                send(Map.of("type", "heartbeat")); return;
            }
            if (connecting || System.nanoTime() < nextAttempt) return;
            connecting = true;
            var uri = URI.create(options.origin().replaceFirst("^http", "ws") + "/api/v1/events/" + identity.serverUuid());
            http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + identity.serverToken()).buildAsync(uri, new Listener()).whenComplete((connection, error) -> {
                connecting = false;
                if (error != null) {
                    Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause();
                    if (cause instanceof WebSocketHandshakeException handshake && (handshake.getResponse().statusCode() == 401 || handshake.getResponse().statusCode() == 403)) { fatal = true; status = "Relay credentials rejected; no automatic re-registration"; notice.accept(status); }
                    else retry();
                }
            });
        } catch (java.io.IOException e) {
            if (java.nio.file.Files.exists(identityFile) && identity == null) { fatal = true; status = "Invalid local relay identity; refusing to replace credentials"; notice.accept(status); }
            else retry();
        } catch (Exception e) { retry(); }
    }
    private void retry() {
        long seconds = Math.min(60, 1L << Math.min(6, attempts++)); nextAttempt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(seconds * 1000 + ThreadLocalRandom.current().nextInt(500));
        status = "Relay unavailable; local collection continues (retrying)";
    }
    private synchronized CompletableFuture<Void> send(Object message) {
        var ws = socket;
        if (ws == null || closed) return CompletableFuture.failedFuture(new IllegalStateException("Relay disconnected"));
        if (queued >= 32) { ws.abort(); disconnected(ws); return CompletableFuture.failedFuture(new IllegalStateException("Relay queue full")); }
        queued++;
        var result = writes.handle((v, e) -> null).thenCompose(v -> ws.sendText(json.writeValueAsString(message), true)).orTimeout(10, TimeUnit.SECONDS).thenApply(v -> (Void) null);
        writes = result;
        result.whenComplete((v, error) -> { synchronized (RelayClient.this) { queued--; } if (error != null) { ws.abort(); disconnected(ws); } });
        return result;
    }
    public CompletableFuture<String> dashboard() {
        return command("dashboard.create", Map.of()).thenApply(node -> options.link(node.get("dashboardId").asString()));
    }
    public CompletableFuture<Void> trust(String code) {
        if (code == null || !code.matches("[a-f0-9]{16}")) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid browser approval code"));
        return command("viewer.trust", Map.of("code", code)).thenApply(node -> null);
    }
    private CompletableFuture<JsonNode> command(String type, Map<String, String> values) {
        if (!options.enabled() || closed || fatal) return CompletableFuture.failedFuture(new IllegalStateException(status));
        wanted = true;
        var result = new CompletableFuture<JsonNode>(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        awaitConnection(type, values, result, deadline); return result;
    }
    private void awaitConnection(String type, Map<String, String> values, CompletableFuture<JsonNode> result, long deadline) {
        if (closed || fatal || System.nanoTime() > deadline) { result.completeExceptionally(new IllegalStateException(status)); return; }
        if (socket == null) { control.schedule(() -> awaitConnection(type, values, result, deadline), 200, TimeUnit.MILLISECONDS); return; }
        String id = UUID.randomUUID().toString(); var frame = new HashMap<String, Object>(values); frame.put("type", type); frame.put("requestId", id);
        commands.put(id, result); result.orTimeout(15, TimeUnit.SECONDS).whenComplete((v, e) -> commands.remove(id));
        send(frame).whenComplete((v, e) -> { if (e != null) result.completeExceptionally(e); }); // writes are never replayed
    }
    private void rpc(WebSocket source, JsonNode node) {
        String id = node.path("requestId").asString();
        synchronized (seen) { if (seen.contains(id)) return; if (seen.size() >= 1024) seen.remove(seen.iterator().next()); seen.add(id); }
        if (!id.matches("[a-f0-9-]{36}")) return;
        try {
            requests.execute(() -> {
                try {
                    if (source != socket) return;
                    var result = service.request(node.path("method").asString(), node.path("path").asString(), node.path("body").asString()).get(50, TimeUnit.SECONDS);
                    byte[] bytes = json.writeValueAsBytes(result.body());
                    if (bytes.length > 32 * 1024 * 1024) { result = new ProfilerService.Result(413, Map.of("error", "Report exceeds relay limit")); bytes = json.writeValueAsBytes(result.body()); }
                    for (int offset = 0, index = 0; offset < bytes.length; index++) {
                        int end = Math.min(bytes.length, offset + 32768);
                        if (source != socket) return;
                        send(Map.of("type", "rpc.chunk", "requestId", id, "status", result.status(), "index", index, "final", end == bytes.length, "data", Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, offset, end)))).get(10, TimeUnit.SECONDS);
                        offset = end;
                    }
                } catch (Exception e) { if (source == socket) send(Map.of("type", "rpc.chunk", "requestId", id, "status", 502, "index", 0, "final", true, "data", Base64.getEncoder().encodeToString("{\"error\":\"Local request failed; do not automatically retry writes\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))); }
            });
        } catch (RejectedExecutionException busy) { source.abort(); disconnected(source); }
    }
    private synchronized void disconnected(WebSocket source) {
        if (socket != source) return;
        socket = null; subscribed = false; status = "Relay disconnected; local collection continues"; retry();
        commands.values().forEach(f -> f.completeExceptionally(new IllegalStateException("Relay disconnected; operation was not retried"))); commands.clear();
    }
    private final class Listener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();
        @Override public void onOpen(WebSocket ws) {
            if (closed) { ws.abort(); return; }
            socket = ws; received = System.nanoTime(); attempts = 0; status = "Relay connected";
            send(Map.of("type", "hello", "protocol", 1, "bootId", bootId)); ws.request(1);
        }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            if (text.length() + data.length() > 96 * 1024) { ws.abort(); disconnected(ws); return null; }
            text.append(data);
            if (last) {
                try {
                    received = System.nanoTime(); var node = json.readTree(text.toString());
                    switch (node.path("type").asString()) {
                        case "subscription" -> subscribed = node.path("enabled").asBoolean();
                        case "heartbeat" -> { }
                        case "command.result" -> { var future = commands.remove(node.path("requestId").asString()); if (future != null) { if (node.has("error")) future.completeExceptionally(new IllegalStateException(node.get("error").asString())); else future.complete(node); } }
                        case "rpc.request" -> rpc(ws, node);
                        default -> { ws.abort(); disconnected(ws); }
                    }
                } catch (Exception invalid) { ws.abort(); disconnected(ws); }
                text.setLength(0);
            }
            ws.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { disconnected(ws); return null; }
        @Override public void onError(WebSocket ws, Throwable error) { disconnected(ws); }
    }
    @Override public void close() {
        closed = true; control.shutdownNow(); requests.shutdownNow();
        try { subscription.close(); } catch (Exception ignored) { }
        var ws = socket; if (ws != null) { socket = null; ws.abort(); }
        commands.values().forEach(f -> f.completeExceptionally(new IllegalStateException("Proxy stopped"))); commands.clear(); http.close();
    }
}
