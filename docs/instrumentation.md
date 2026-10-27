# Instrumentation and extension boundaries

## Native Velocity bridge

The integration is pinned to Velocity 4.3.0 build 44, commit `4d93ece85bb38b80d82aaf5b03bb33c1355bd6b4`, distribution SHA-256 `85be73b90f295acd8d5d45a00ad10bac791d4b72b7554172612a89565ebaaa8f`.

Velocity has no public channel instrumentation API. `NativeVelocityProvider` isolates access to its private connection manager and internal `ServerChannelInitializerHolder`. It validates reflected members/types, wraps only the client initializer and delegates to the existing initializer before adding the counter. The real pipeline is checked by handler class, not assumed handler names. Netty 4.2.19.Final is provided by the proxy and not bundled.

The replacement is installed during `ProxyInitializeEvent`, before listeners bind. A bootstrap captures the initializer at bind time, so changing the holder after bind is insufficient. Hot loading and reload tools are unsupported. Other plugins that replace or remove initializers/handlers without preserving delegates can break coverage; their combinations must be tested. The profiler cannot promise universal compatibility with arbitrary pipeline mutations.

Shutdown deactivates the wrapper, restores the holder only if it still owns that slot, and removes its own handlers. The wrapper exposes a mutable `ChannelInitializer` delegate so PacketEvents 2.14 can unwrap its own initializer if it shuts down first. Restoration uses the current delegate, never a cached wrapper belonging to an already-terminated plugin. If another plugin wrapped it, the deactivated wrapper keeps delegating without collecting. Proxy connections and event-loop groups are never closed by this plugin.

## PacketEvents bridge

`PacketEventsProvider` is loaded only when explicitly selected. PacketEvents must already be initialized and no users may have connected before registration. It observes `UserConnectEvent` and installs the same counter at the pipeline head. It never cancels an event or manages PacketEvents' global lifecycle. Exactly one provider is registered.

PacketEvents also uses platform internals; it centralizes some compatibility work but does not eliminate it. Its packet event buffers describe a protocol-layer representation, with packet ID already consumed; they cannot be summed as transport bytes.

`PacketEventsCaptureProvider` works with either discovery strategy and requires external initialized PacketEvents 2.14.0. When lightweight statistics are enabled, one MONITOR listener stays registered and only updates direction counters; packet type lookup and detailed attribution counters are activated for a capture. With statistics disabled, the listener exists only during captures. It never changes global PacketEvents configuration or lifecycle. Only channels containing our owned transport handler qualify, so backend pipelines cannot be added to the same counters.

The exact PacketEvents 2.14 Velocity decoder/encoder copies the input to a zero-based transformed buffer, consumes the packet ID, then restores the post-ID reader index between listeners. The observer validates a reader index of 1–5 and reads `writerIndex()` to include the actual encoded ID plus payload. It never reads fields, advances indexes, retains, releases, copies or clones the buffer. This boundary is implementation-specific; unsupported library versions are rejected until validated. PacketEvents itself has baseline processing/buffer-copying overhead; installing it is not free even if our analysis listener is idle.

Cancellation is observed at MONITOR, not guaranteed final for all possible listeners. Cancelled observations are counted separately and excluded from byte/packet totals. A pending wrapper re-encode is flagged, but final re-encoded size is not claimed. OUT protocol observations are not successful-write promises. Compression, encryption, framing, PROXY headers, later cancellations/modifications and failed writes explain differences from Overview transport totals. Packet contents are never stored.

Descriptors use logical enum identity, protocol state and direction, not raw numeric IDs. The catalog is bounded to 4,096 types; hot-path work consists of an identity-map lookup, fixed counter updates and a per-thread stop gate. First-use bookkeeping/LongAdder stripe growth may allocate; no event strings or per-packet report objects are constructed. Stopping disables observations immediately; an independent worker unregisters and quiesces in-flight observations before creating the immutable final report. Serialization/checkpoints/file IO stay off Netty event loops. Live counters are weakly consistent, final counters are frozen.

### Connection, player and server association

Each owned client channel has one bounded registry connection and an immutable observation context. Identity is attached at `PostLoginEvent`. The adapter awaits a client-event-loop update during `ServerConnectedEvent`, using the event's confirmed server rather than `Player.getCurrentServer()` (which can still refer to the previous server at this point). In the pinned Velocity implementation this event precedes forwarding the successful JoinGame and installing the new connected server. No pre-connect request is treated as success, so cancelled/failed requests do not reassign traffic. Until confirmation, the previous known association is retained; pre-login is explicitly unassigned. This is association, not causality: proxy-generated packets can be included and no separate backend socket is measured.

An observation captures one immutable association before updating counters. Switching servers never moves earlier bytes; a reconnect gets a new physical connection ID while keeping the player UUID. Capture scopes are matched before collecting type rows. Per-dimension counters are allocated on first use only, capped by `max-attribution-rows`; later unseen dimensions at the cap are skipped and flagged, while scope totals remain intact. Lightweight connection/server registries and disconnected retention are separately bounded. No player IP, packet payload, persistent background report, or per-player WebSocket is created. Snapshot aggregation, report serialization and IO stay on independent workers.

### Capture throughput and population

An off-event-loop worker samples cumulative direction/dimension counters approximately once per second, retaining sparse interval deltas. Stop freezes observers and retains the final partial interval. Actual monotonic intervals determine rates; scheduler/disk delays are not silently filled with zero samples. Min/max are interval averages, including measured idle zero, not packet bursts; means use the full selected capture duration. Filtering uses historical contexts, never reassigns old bytes, and exposes gaps when bounded attribution/timeline capacity is insufficient. Schema-1/2 files have no timeline to recover.

Population samples use Velocity's real player collection and current server, separately from the bounded connection registry. Pre-login/status sockets never inflate global player counts. The sampler retains requested identity → current-server maps so saved PLAYER/CONNECTION views can follow transfers without rewriting history. Offline/unassigned identities have unknown local population; registered empty servers can have measured zero. Counts are point-in-time observations, not averages across the traffic interval or a causal attribution. Unchanged maps are carried forward; sparse deltas/maps and sample counts have separate configured bounds, with explicit incomplete flags. Population/temporal aggregation creates no per-packet timeline objects.

Dashboard bandwidth capacities are independent IN/OUT decimal Mbps references stored in a separate local atomic JSON file. They affect graph scaling/labels only, never handlers, schedulers, counters or traffic flow. Remote settings changes require an approved browser, exact Origin and session CSRF.

### Outgoing hosted relay

`profiler-server` now contains local allowlisted services/file stores only; no Javalin, Jetty or HTTP/static listener is embedded. `profiler-relay` asynchronously registers a private identity and uses an authenticated outgoing WebSocket when requested. RPC serialization, bounded report chunking and network work execute off packet event loops. Relay failure cannot disable collection, captures or local storage, and dashboard expiry does not stop a capture. Failed writes are never automatically replayed.

A random relay boot UUID identifies each plugin startup, independently of the resettable transport session ID. The hosted backend retains bounded temporary transport snapshots, shared by dashboards for that boot, to show stale history during disconnects. Reconnection preserves the boot; restart starts a new series. Reports/settings are accessed on demand from Minecraft and are not durably archived in the relay. Node uses private single-process atomic file state; Cloudflare uses native Durable Object hibernation, storage and alarms. Per-browser approval and forty-minute reconnection grace are distinct from shared sample retention. See [hosting](hosting.md) for limits, trust and cleanup semantics.

### Modded registry compatibility

PacketEvents 2.14's own `InternalPacketListener` decodes synchronized registries independently of our on-demand listener. Modded entries can fail resolution, for example `biomesoplenty:binary` in `minecraft:particle_type` or `create:potato_cannon` in `minecraft:item`. A stack through `SynchronizedRegistriesHandler` / `InternalPacketListener` indicates this separate PacketEvents mapping limitation, not payload parsing by our profiler. Status/ping tests do not cover modded login/configuration registries.

Disabling `captures.enabled` does **not** disable PacketEvents' internal registry listener while its plugin remains installed. This profiler does not remove shared internal listeners, change global settings or fabricate mappings to suppress those errors. A PacketEvents-side fix must be validated with the affected modpack. If no other plugin requires PacketEvents, the fallback is to stop the proxy, remove the external PacketEvents plugin and keep `traffic.channel-provider: "native"`; transport metrics remain available, but packet-type analysis is unavailable. Do not remove a dependency required by other plugins.

## Accounting limits

The handler sees `ByteBuf` / `ByteBufHolder` readable bytes without retaining/copying payload. Other outbound object types are not claimed as measured transport bytes; the pinned Minecraft pipeline produces buffers at this point. Successful promises count writes once, not acknowledgements. Partial failures may undercount socket bytes.

Reads and successful writes can straddle sampling boundaries. `LongAdder` counters provide efficient concurrent accumulation, not an atomic cross-direction transaction. A completion captures its session so reset cannot transfer old writes to new totals. Rates and peaks are interval averages. TCP/IP overhead, retransmissions, query UDP and backend channels are outside scope. PROXY protocol headers are included in IN.

## Future adapters

Adapters implement platform metadata/capabilities and provide instrumentation through the existing collector/session contract. Core has no Velocity, Netty or PacketEvents dependency. Java requirements belong to the adapter, not the core.

Potential future targets: BungeeCord, Paper, Folia/Pinac, Fabric and NeoForge. No support is implied until each adapter's discovery, direction, lifecycle and coverage are verified. PacketEvents availability alone does not establish platform support or plugin attribution.
