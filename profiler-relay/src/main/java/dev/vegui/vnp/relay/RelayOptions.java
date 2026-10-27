package dev.vegui.vnp.relay;

import java.net.URI;

public record RelayOptions(boolean enabled, URI endpoint, URI publicWeb) {
    public RelayOptions { validate(endpoint); validate(publicWeb); }
    private static void validate(URI uri) {
        boolean local = "http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost()));
        if (!("https".equals(uri.getScheme()) || local) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Relay URLs must be HTTPS origins (HTTP localhost for development only)");
    }
    public String origin() { return endpoint.toString().replaceAll("/$", ""); }
    public String link(String id) { return publicWeb.toString().replaceAll("/$", "") + "/" + id; }
}
