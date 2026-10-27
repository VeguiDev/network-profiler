package dev.vegui.vnp.api;

/** Metadata contract for future connection providers; never exposes a platform channel. */
public interface NetworkConnection {
    String id();
    boolean active();
}
