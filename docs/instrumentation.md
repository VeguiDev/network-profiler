# Instrumentation and extension boundaries

## Native Velocity bridge

The integration is pinned to Velocity 4.3.0 build 44, commit `4d93ece85bb38b80d82aaf5b03bb33c1355bd6b4`, distribution SHA-256 `85be73b90f295acd8d5d45a00ad10bac791d4b72b7554172612a89565ebaaa8f`.

Velocity has no public channel instrumentation API. `NativeVelocityProvider` isolates access to its private connection manager and internal `ServerChannelInitializerHolder`. It validates reflected members/types, wraps only the client initializer and delegates to the existing initializer before adding the counter. The real pipeline is checked by handler class, not assumed handler names. Netty 4.2.19.Final is provided by the proxy and not bundled.

The replacement is installed during `ProxyInitializeEvent`, before listeners bind. A bootstrap captures the initializer at bind time, so changing the holder after bind is insufficient. Hot loading and reload tools are unsupported. Other plugins that replace or remove initializers/handlers without preserving delegates can break coverage; their combinations must be tested. The profiler cannot promise universal compatibility with arbitrary pipeline mutations.

Shutdown deactivates the wrapper, restores the holder only if it still owns that slot, and removes its own handlers. If another plugin wrapped it, the deactivated wrapper keeps delegating without collecting. Proxy connections and event-loop groups are never closed by this plugin.

## PacketEvents bridge

`PacketEventsProvider` is loaded only when explicitly selected. PacketEvents must already be initialized and no users may have connected before registration. It observes `UserConnectEvent` and installs the same counter at the pipeline head. It never cancels an event or manages PacketEvents' global lifecycle. Exactly one provider is registered.

PacketEvents also uses platform internals; it centralizes some compatibility work but does not eliminate it. Its packet event buffers describe a protocol-layer representation, with packet ID already consumed; they cannot be summed as transport bytes. The bridge currently performs no packet analysis and does not enable `PACKET_TRAFFIC`.

## Accounting limits

The handler sees `ByteBuf` / `ByteBufHolder` readable bytes without retaining/copying payload. Other outbound object types are not claimed as measured transport bytes; the pinned Minecraft pipeline produces buffers at this point. Successful promises count writes once, not acknowledgements. Partial failures may undercount socket bytes.

Reads and successful writes can straddle sampling boundaries. `LongAdder` counters provide efficient concurrent accumulation, not an atomic cross-direction transaction. A completion captures its session so reset cannot transfer old writes to new totals. Rates and peaks are interval averages. TCP/IP overhead, retransmissions, query UDP and backend channels are outside scope. PROXY protocol headers are included in IN.

## Future adapters

Adapters implement platform metadata/capabilities and provide instrumentation through the existing collector/session contract. Core has no Velocity, Netty or PacketEvents dependency. Java requirements belong to the adapter, not the core.

Potential future targets: BungeeCord, Paper, Folia/Pinac, Fabric and NeoForge. No support is implied until each adapter's discovery, direction, lifecycle and coverage are verified. PacketEvents availability alone does not establish platform support or plugin attribution.
