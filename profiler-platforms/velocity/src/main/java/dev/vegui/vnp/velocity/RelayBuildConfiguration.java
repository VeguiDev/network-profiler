package dev.vegui.vnp.velocity;

import dev.vegui.vnp.relay.RelayOptions;
import java.io.IOException;
import java.net.URI;
import java.util.Properties;

/** Deployment origins are baked into the JAR; never read runtime environment variables or YAML URLs. */
final class RelayBuildConfiguration {
    private static final RelayOptions COMPILED = load();
    private RelayBuildConfiguration() {}

    static RelayOptions options(boolean enabled) {
        return new RelayOptions(enabled, COMPILED.endpoint(), COMPILED.publicWeb());
    }

    private static RelayOptions load() {
        var values = new Properties();
        try (var input = RelayBuildConfiguration.class.getResourceAsStream("/vnp-relay.properties")) {
            if (input == null) throw new IOException("Missing build-time relay configuration");
            values.load(input);
            return new RelayOptions(true, URI.create(values.getProperty("endpoint")), URI.create(values.getProperty("public-web-url")));
        } catch (IOException | RuntimeException invalid) {
            throw new ExceptionInInitializerError(invalid);
        }
    }
}
