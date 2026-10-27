package dev.vegui.vnp.api;

import java.util.Set;

public record CaptureScope(String type, String id) {
    public static final CaptureScope GLOBAL = new CaptureScope("GLOBAL", null);
    public CaptureScope {
        if (type == null || !Set.of("GLOBAL", "SERVER", "PLAYER", "CONNECTION").contains(type)
                || type.equals("GLOBAL") && id != null || !type.equals("GLOBAL") && (id == null || id.isBlank() || id.length() > 256))
            throw new IllegalArgumentException("Scope must be GLOBAL or SERVER/PLAYER/CONNECTION with an id");
        if (type.equals("PLAYER") || type.equals("CONNECTION")) java.util.UUID.fromString(id);
    }
    public boolean matches(PacketContext context) {
        return switch (type) {
            case "GLOBAL" -> true;
            case "SERVER" -> id.equals(context.serverId());
            case "PLAYER" -> id.equals(context.playerId());
            case "CONNECTION" -> id.equals(context.connectionId());
            default -> false;
        };
    }
}
