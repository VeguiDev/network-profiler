package dev.vegui.vnp.api;

import java.util.List;

/** Platform bridge. Implementations never change packet contents or own the external library lifecycle. */
public interface PacketCaptureProvider {
    List<PacketCaptureReport.Descriptor> descriptors();
    default boolean available() { return true; }
    void start(Recorder recorder);
    void stop();
    interface Recorder {
        void record(int index, int bytes, boolean cancelled, boolean modified);
        default void record(PacketContext context, int index, int bytes, boolean cancelled, boolean modified) {
            record(index, bytes, cancelled, modified);
        }
        default void connectionClosed(String connectionId) {}
        void error();
    }
}
