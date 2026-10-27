package dev.networkprofiler.velocity;

import dev.networkprofiler.server.ServerOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public record ProfilerConfig(boolean enabled, int historySeconds, boolean httpEnabled, ServerOptions http,
                             boolean trafficEnabled, boolean inbound, boolean outbound, String provider) {
    public static ProfilerConfig load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path path = directory.resolve("config.yml");
        if (!Files.exists(path)) {
            try (var input = ProfilerConfig.class.getResourceAsStream("/config.yml")) {
                if (input == null) throw new IOException("Missing bundled configuration");
                Files.copy(input, path);
            }
        }
        var limits = new LoaderOptions();
        limits.setAllowDuplicateKeys(false); limits.setMaxAliasesForCollections(0); limits.setCodePointLimit(32768);
        Map<String, Object> root;
        try (var input = Files.newInputStream(path)) {
            Object document = new Yaml(new SafeConstructor(limits)).load(input);
            if (!(document instanceof Map<?, ?>)) throw new IllegalArgumentException("Configuration must be a YAML mapping");
            root = map(document);
        }
        var p = section(root, "profiler", Set.of("enabled", "sampling-interval-ms", "history-duration-seconds"));
        var h = section(root, "http", Set.of("enabled", "host", "port", "allowed-hosts", "allowed-origins", "max-websocket-clients"));
        var t = section(root, "traffic", Set.of("enabled", "track-inbound", "track-outbound", "channel-provider"));
        rejectUnknown(root, Set.of("profiler", "http", "traffic"));
        if (integer(p, "sampling-interval-ms", 1000, 1000, 1000) != 1000) throw new IllegalArgumentException("MVP sampling interval is 1000ms");
        String provider = text(t, "channel-provider", "native");
        if (!Set.of("native", "packetevents").contains(provider)) throw new IllegalArgumentException("Unknown channel-provider: " + provider);
        Set<String> hosts = strings(h, "allowed-hosts", Set.of("127.0.0.1", "localhost", "[::1]"));
        for (String host : hosts) if (!host.equals(host.toLowerCase(Locale.ROOT)) || host.contains("/") || host.contains("*") || host.isBlank())
            throw new IllegalArgumentException("allowed-hosts requires explicit lowercase hostnames");
        return new ProfilerConfig(bool(p, "enabled", true), integer(p, "history-duration-seconds", 900, 1, 86400),
                bool(h, "enabled", true), new ServerOptions(text(h, "host", "127.0.0.1"), integer(h, "port", 8765, 1, 65535),
                hosts, strings(h, "allowed-origins", Set.of()), integer(h, "max-websocket-clients", 16, 1, 256)),
                bool(t, "enabled", true), bool(t, "track-inbound", true), bool(t, "track-outbound", true), provider);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    private static Map<String, Object> section(Map<String, Object> root, String key, Set<String> allowed) {
        Object value = root.getOrDefault(key, Map.of());
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException(key + " must be a mapping");
        var result = map(value); rejectUnknown(result, allowed); return result;
    }
    private static void rejectUnknown(Map<String, Object> map, Set<String> allowed) {
        for (Object key : map.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown configuration key: " + key);
    }
    private static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.getOrDefault(key, fallback);
        if (!(value instanceof Boolean result)) throw new IllegalArgumentException(key + " must be boolean"); return result;
    }
    private static int integer(Map<String, Object> map, String key, int fallback, int min, int max) {
        Object value = map.getOrDefault(key, fallback);
        if (!(value instanceof Integer number) || number < min || number > max) throw new IllegalArgumentException(key + " must be " + min + ".." + max);
        return number;
    }
    private static String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.getOrDefault(key, fallback);
        if (!(value instanceof String result) || result.isBlank()) throw new IllegalArgumentException(key + " must be nonempty text"); return result;
    }
    private static Set<String> strings(Map<String, Object> map, String key, Set<String> fallback) {
        Object value = map.get(key); if (value == null) return fallback;
        if (!(value instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String)))
            throw new IllegalArgumentException(key + " must be a list of strings");
        return Set.copyOf(list.stream().map(String.class::cast).toList());
    }
}
