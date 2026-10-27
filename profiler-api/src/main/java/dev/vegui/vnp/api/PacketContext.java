package dev.vegui.vnp.api;

/** Immutable association at observation time, NOT proof of a packet's backend origin. No IP addresses. */
public record PacketContext(String connectionId, String playerId, String playerName, String serverId) {
    public static final String UNASSIGNED = "~unassigned";
    public static final String TRANSITION = "~transition";
    public static final PacketContext UNKNOWN = new PacketContext("", null, null, UNASSIGNED);
    public PacketContext {
        if (connectionId == null || serverId == null || connectionId.length() > 256 || serverId.length() > 256
                || playerId != null && playerId.length() > 64 || playerName != null && playerName.length() > 64)
            throw new IllegalArgumentException("Invalid packet association");
    }
}
