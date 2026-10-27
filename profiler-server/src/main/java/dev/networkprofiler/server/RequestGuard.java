package dev.networkprofiler.server;

import java.net.URI;
import java.util.Locale;

final class RequestGuard {
    private final ServerOptions options;
    RequestGuard(ServerOptions options) { this.options = options; }
    boolean hostAllowed(String host) {
        try {
            var uri = URI.create("http://" + host);
            return host != null && uri.getHost() != null && uri.getRawUserInfo() == null
                    && uri.getRawPath().isEmpty() && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && options.allowedHosts().contains(uri.getHost().toLowerCase(Locale.ROOT));
        } catch (RuntimeException invalid) { return false; }
    }
    boolean originAllowed(String host, String origin) {
        if (origin == null) return true; // Non-browser clients still pass Host validation; this is not authentication.
        try {
            var uri = URI.create(origin);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
            return uri.getRawAuthority().equalsIgnoreCase(host) || options.allowedOrigins().contains(origin);
        } catch (RuntimeException invalid) { return false; }
    }
}
