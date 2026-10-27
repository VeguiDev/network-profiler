package dev.vegui.vnp.velocity;

import dev.vegui.vnp.relay.RelayOptions;
import dev.vegui.vnp.core.CaptureOptions;
import dev.vegui.vnp.core.StatisticsOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public record ProfilerConfig(boolean enabled, int historySeconds, RelayOptions relay,
                             boolean trafficEnabled, boolean inbound, boolean outbound, String provider,
                               boolean capturesEnabled, CaptureOptions captures, StatisticsOptions statistics) {
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
        var h = section(root, "http", Set.of("enabled", "host", "port", "allowed-hosts", "allowed-origins", "max-websocket-clients", "public-base-url"));
        // Legacy HTTP keys are accepted for upgrades, but never open a listener or define relay credentials.
        var r = section(root, "relay", Set.of("enabled", "endpoint", "public-web-url"));
        var t = section(root, "traffic", Set.of("enabled", "track-inbound", "track-outbound", "channel-provider"));
        var c = section(root, "captures", Set.of("enabled", "max-duration-seconds", "max-reports", "retention-days", "max-attribution-rows", "max-timeline-entries", "max-timeline-samples"));
        var s = section(root, "statistics", Set.of("enabled", "interval-seconds", "max-connections", "max-servers", "disconnected-retention-seconds"));
        rejectUnknown(root, Set.of("profiler", "http", "relay", "traffic", "captures", "statistics"));
        if (integer(p, "sampling-interval-ms", 1000, 1000, 1000) != 1000) throw new IllegalArgumentException("MVP sampling interval is 1000ms");
        String provider = text(t, "channel-provider", "native");
        if (!Set.of("native", "packetevents").contains(provider)) throw new IllegalArgumentException("Unknown channel-provider: " + provider);
        return new ProfilerConfig(bool(p, "enabled", true), integer(p, "history-duration-seconds", 900, 1, 86400),
                new RelayOptions(bool(r, "enabled", true), java.net.URI.create(text(r, "endpoint", "https://vnp.vegui.dev")),
                        java.net.URI.create(text(r, "public-web-url", "https://vnp.vegui.dev"))),
                bool(t, "enabled", true), bool(t, "track-inbound", true), bool(t, "track-outbound", true), provider,
                bool(c, "enabled", true), new CaptureOptions(integer(c, "max-reports", 50, 1, 1000),
                 integer(c, "retention-days", 7, 1, 365), integer(c, "max-duration-seconds", 900, 1, 86400),
                  integer(c, "max-attribution-rows", 10000, 1, 20000), integer(c, "max-timeline-entries", 10000, 1, 20000),
                  integer(c, "max-timeline-samples", 3602, 2, 86402)),
                 new StatisticsOptions(bool(s, "enabled", true), integer(s, "interval-seconds", 30, 1, 300),
                 integer(s, "max-connections", 10000, 1, 100000), integer(s, "max-servers", 1000, 1, 10000),
                 integer(s, "disconnected-retention-seconds", 900, 0, 86400)));
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
