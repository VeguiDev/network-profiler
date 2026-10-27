# Vegui Network Profiler (VNP)

<p align="center">
  <img src="docs/assets/vnp-logo.png" alt="Vegui Network Profiler logo" width="220" />
</p>

A modular, open-source network profiler for Minecraft. Measure **real client ↔ proxy transport traffic**, inspect server/connection statistics and run scoped packet analysis. A separately hosted **Hono API + Astro/React dashboard** connects to the plugin through an outgoing authenticated WebSocket. Minecraft hosts no HTTP listener or web assets. No packet-content capture or synthetic production data.

## Compatibility

| Component | Requirement |
| --- | --- |
| Velocity plugin | **Velocity 4.3.0**, tested against build 44; **Java 25+** |
| Shared Java modules | Java 21+ |
| Build | JDK 21 and JDK 25 installed; Gradle Wrapper included |
| Frontend development/build | Node.js 24.15+ and npm |
| PacketEvents | External **2.14.0 required for protocol statistics and packet analysis**; optional for transport totals |

Velocity 4.x itself requires Java 25. A Java 21-compatible core does **not** make the latest Velocity run on Java 21. Other Velocity releases are deliberately rejected until their pipeline integration is validated. Installation is at proxy startup; plugin hot loading is unsupported.

## Build

```sh
./gradlew build
```

On Windows use `gradlew.bat build`. Gradle automatically detects common JDK installations. For custom locations, set `org.gradle.java.installations.paths` in your **local** Gradle configuration. The build runs Java, backend and frontend tests, type-checks the backend, builds Astro separately and produces:

```text
profiler-platforms/velocity/build/libs/vegui-network-profiler-velocity-0.1.0.jar
```

Install **this JAR**, not the `-thin` or `-sources` artifacts. It does not contain the dashboard, Javalin or Jetty. The shared modules target Java 21 and the Velocity adapter targets Java 25. Dependencies are pinned in the Gradle catalog and each npm lockfile. Astro assets are in `profiler-web/dist/`. See [hosted deployment](docs/hosting.md) for Node and Cloudflare Workers + Durable Objects.

## Install and open the dashboard

1. Deploy the hosted backend/dashboard at your HTTPS origin, for example `https://vnp.vegui.dev` (not a bundled public service).
2. Stop Velocity, install the JAR and configure `relay.endpoint` and `relay.public-web-url` for that origin.
3. Start Velocity with Java 25+. Registration runs asynchronously and saves a private `relay-identity.json`; relay outages never stop local collection.
4. Run `/vnp dashboard`, open the generated link, then approve **that browser** with `/vnp trustSession <code>` shown on its pending page.
5. The approved browser can read metrics/reports, start/stop captures and edit Settings. A different browser needs its own approval, even with the same link.

No inbound dashboard port, SSH tunnel or reverse proxy is required on Minecraft. The hosted Node service still needs HTTPS termination; Cloudflare provides the hosted HTTPS endpoint. The plugin opens its outgoing socket on dashboard/trust commands and reconnects automatically without creating extra dashboard links.

The plugin creates `plugins/vnp/config.yml`. Changes require a proxy restart. Commands require `vnp.admin` (or the Velocity console). Use `/vnp`:

| Command | Action |
| --- | --- |
| `/vnp dashboard` | Create a dashboard link through the outgoing relay connection |
| `/vnp trustSession <code>` | Approve one pending browser for full access; single-use code, valid ten minutes |
| `/vnp start [seconds]` | Start the single shared packet capture; defaults to the configured maximum duration |
| `/vnp start [seconds] --server <name>` | Capture only packets associated with this server |
| `/vnp start [seconds] --player <name\|uuid>` | Follow the player UUID across transfers/reconnections |
| `/vnp start [seconds] --connection <uuid>` | Capture one physical connection; stops on disconnect |
| `/vnp stop` | Save the capture locally; open Reports in an approved dashboard |
| `/vnp status` | Transport, packet capture and relay state |
| `/vnp reset` | Reset transport counters/history only; an active packet capture is independent |

## Configuration

```yaml
profiler:
  enabled: true
  sampling-interval-ms: 1000
  history-duration-seconds: 900
relay:
  enabled: true
  endpoint: "https://vnp.vegui.dev"
  public-web-url: "https://vnp.vegui.dev"
traffic:
  enabled: true
  track-inbound: true
  track-outbound: true
  channel-provider: "native"
captures:
  enabled: true
  max-duration-seconds: 900
  max-reports: 50
  retention-days: 7
  max-attribution-rows: 10000
  max-timeline-entries: 10000
  max-timeline-samples: 3602
statistics:
  enabled: true
  interval-seconds: 30
  max-connections: 10000
  max-servers: 1000
  disconnected-retention-seconds: 900
```

- The MVP accepts a **1,000 ms** sampling interval. Actual elapsed time, not an assumed second, determines the reported rate.
- History accepts 1–86,400 seconds; default 900. Both sample capacity and elapsed retention are bounded. No backfilled/fabricated samples after scheduler delays.
- Unknown keys, duplicate YAML keys, unsafe YAML tags, invalid types and out-of-range values are rejected.
- Disabled directions have `available: false` and null values, not misleading zero values. Combined totals are unavailable when either direction is disabled.
- `relay.enabled: false` leaves collection and local commands active without remote access. Legacy known `http` keys are accepted for migration but ignored: they no longer open a listener. Replace that section with `relay`.
- An incompatible integration is explicitly unhealthy; inspect console logs and `/api/v1/status` when available.
- Capture duration accepts 1–86,400 seconds; saved report count 1–1,000; retention 1–365 days. Reports live in `plugins/vnp/reports/` as versioned JSON, at most 32 MiB each. Count/age limits also apply to disk files and active checkpoints. Attribution is capped at 1–20,000 dimension/type rows; hitting the cap preserves capture totals but explicitly marks attribution incomplete.
- Lightweight statistics count protocol bytes continuously and publish interval averages every 30 seconds by default (configurable 1–300). Limits: 1–100,000 active/recent connections, 1–10,000 server identities, and 0–86,400 seconds of disconnected retention. Closed connections are published one final time before pruning. Limits or observation failures mark coverage incomplete rather than inventing data. These statistics are in memory, not automatic persisted reports.
- Capture timelines sample every second, plus the final partial interval. They retain at most `max-timeline-samples` points (2–86,402) and `max-timeline-entries` sparse dimension/population entries (1–20,000). Reaching a limit marks history incomplete without stopping cumulative counters. Missing coverage is shown as unknown/gaps, never fabricated zero traffic. Unchanged population maps are stored once and carried forward.
- Relay URLs must be HTTPS origins without credentials, query or subpath; HTTP is allowed only on loopback for development. Serve the API and Astro assets on the same public origin. The private identity file binds credentials to the relay endpoint; an endpoint change or corrupt identity is explicitly refused rather than silently replacing credentials. Back it up privately; deliberate migration requires provisioning a new identity while retaining local report/settings files.

## What IN and OUT mean

**Scope:** only accepted Minecraft **client ↔ Velocity** TCP connections. Backend connections and this plugin's HTTP/WebSocket traffic are not counted. Status/ping, pre-login and logged-in connections are included when installed at startup.

The plugin adds one pass-through Netty handler **before Minecraft framing, compression and encryption codecs**. This observes their transport representation, including Minecraft framing and compressed/encrypted bytes. With Velocity PROXY protocol enabled, the incoming PROXY header is also counted because the counter precedes its decoder.

- **IN:** readable bytes observed arriving from the client's transport.
- **OUT:** readable bytes belonging to writes whose Netty promise completes successfully. Each write is counted once, including originally void promises.
- A successful write is **not** proof that the remote client received or acknowledged the data.
- Failed/cancelled writes do not increment OUT. A partial transport write followed by failure can therefore undercount bytes that reached the socket; the plugin does not claim kernel-level byte accounting.
- The counters exclude TCP/IP/link headers, ACKs, retransmissions and backend traffic. They are **not NIC bandwidth counters**.
- Netty read/write events are not Minecraft packet counts. Multiple packets can share a read or write.
- Totals start at the current profiler session; pre-installation traffic cannot be recovered.

Rates use decimal B/s, KB/s, MB/s, GB/s or bps, Kbps, Mbps, Gbps. Totals use binary KiB, MiB and GiB. The dashboard preserves integer totals without JavaScript floating-point rounding.

## Dashboard

Overview includes current IN/OUT, cumulative totals, peaks, combined transferred bytes, uptime and a real-time Recharts graph. Global player counts use the proxy's actual player API, not tracked/pre-login TCP connections, and have an independent graph axis. Controls provide 1/5/15-minute windows, bytes/bits, individual series visibility, dark/light theme and visualization pause.

Pause freezes presentation only; collection, history and the single shared WebSocket continue. Reconnection uses exponential backoff with jitter, recovers HTTP history and merges by session/sequence. Disconnected values are marked stale. Servers and Connections provide lightweight protocol-byte tables, interval rates/shares and clickable Details; player details are linked from connections.

**Settings** persists separate optional IN/OUT bandwidth capacities in `plugins/vnp/dashboard-settings.json`. Empty disables a reference; values are decimal Mbps, correctly converted for bytes/bits display. The chart extends its scale to show references and traffic, including over-capacity values. These settings are **visual only**, never throttling traffic or altering counters. They apply to Overview and report graphs and are shared by approved browsers; other open clients refresh settings within 30 seconds. Saving uses atomic replacement where supported and does not rewrite YAML. Approval grants full access; there is no separate read-only role.

Install external Velocity PacketEvents 2.14.0, then press **Start analysis** or run `/vnp start`. All controls share **one simultaneous capture**, global or scoped to a server, player UUID or physical connection. Detail pages start a capture for that entity; Overview starts global analysis. `/<dashboardId>/reports/{id}` shows live packet totals once per second while recording, then an immutable report after **Stop & save report** or `/vnp stop`. Reports remain on Minecraft across restarts; access them through a valid approved dashboard. Permanent statistics do not classify packet types; detailed counters exist only during a capture.

Reports group observations by associated server, connection and player, with percentages, filters and Details links. A server transfer creates a new association; previously collected bytes never move to the new server. Player captures follow UUID across transfers and reconnects until stopped or timed out; connection captures stop on socket closure. Only one scope is collected, not an unrelated global capture filtered later. Older schema-1 reports remain readable without fabricated attribution.

Live and saved schema-3 reports include a one-second IN/OUT graph and **current/last interval, mean, minimum and maximum** rates for IN, OUT and combined traffic. Mean is cumulative selected bytes divided by the full actual capture duration; min/max include measured idle zeros and describe interval averages, not microbursts. Server/player/connection filters update packet totals, curves and rate statistics together; packet-name search affects the table only. Graph windows do not shorten the statistics' capture-period denominator. Final fractional intervals are retained; unavailable/incomplete history cannot supply a trustworthy min/max. Schema-1/2 reports remain readable with a clear absence of historical graphs.

Report graphs also show global proxy players and, for a server scope/filter, that server's players. Player/connection scopes follow the **current server at each sample**, including historical transfers. Unassigned or disconnected local identities show unavailable, not zero. Counts are point-in-time observations, not interval averages or a claim that players caused the measured traffic. Report pause stops visualization only; basic statistics still publish every 30 seconds independently.

**Server attribution is association, not packet origin:** only the existing client ↔ proxy observation is measured. Proxy-generated packets cannot reliably be distinguished from backend-originated packets here. Unassigned pre-login observations have an explicit bucket. The associated server changes only after a successful `ServerConnectedEvent`, not a requested/failed transfer. Connection totals can span previous servers; use report attribution for historical breakdowns. Published statistics refresh every 30 seconds and can lag connection/transfer state by one interval.

Tabs **BOTH**, **Outbound**, **Inbound** show logical packet type + protocol state + direction, accumulated bytes, percentage, packet count and mean size. Percentages use bytes for the selected direction (combined for BOTH), not packet counts. Search does not change the denominator. Rows are sorted by bytes; the table is paginated and responsive.

These are **protocol observations**, not the transport metric above: PacketEvents MONITOR sees the encoded packet ID and payload, before compression/encryption and possible later re-encoding. OUT observations are not proof of successful transmission. Packets cancelled at observation are counted separately and excluded from byte/count percentages; pending wrapper re-encodes and observation errors are exposed. Same-priority/later changes can occur, so this is not a final-pipeline audit. Full buffers, fields and payload contents are never saved.

Graceful shutdown finalizes an active capture as `INTERRUPTED`. A partial checkpoint is saved at start and every five seconds; after an unclean shutdown it is recovered as explicitly partial `INTERRUPTED` data, never resumed or presented as complete. Data since the last checkpoint is lost. Save failures appear as `SAVE_FAILED` (memory-only) rather than claiming successful persistence.

## Remote sessions and security

The plugin registers at `POST /api/v1/server` and authenticates `WS /api/v1/events/<serverUuid>` with a Bearer token. Hosted storage keeps only its hash. Dashboard links are routing identifiers, **not authorization**. Each browser gets a private HttpOnly cookie and requires in-game approval before reading any metrics. Browser writes also require same-origin Origin, JSON and a session CSRF token. No arbitrary HTTP tunnel, console command execution or automatic write replay is exposed.

If either Minecraft or that browser disconnects, a **40-minute reconnection grace** begins. It is cancelled only when both return before expiry; another viewer cannot renew this browser's authorization. Unapproved codes expire after ten minutes. Expiry revokes access but does not delete local reports, stop captures or reset Settings.

The relay retains bounded **temporary transport statistics per Minecraft startup**: up to two boot series, 900 points per series and 2 MiB combined per registered server. Reconnect preserves a boot; restart creates a new one, and `/vnp reset` creates a new transport stream within the boot. Different dashboards share these temporary samples, not authorization. Offline dashboards show stale history and a disconnected warning; unavailable operations are disabled rather than displaying invented zeros. Local captures/reports/Settings are fetched on demand and are not archived in the cloud. Temporary samples are removed after the last relevant dashboard expires. The hosted operator is trusted to relay data and authorize sessions; TLS does not make the relay end-to-end encrypted.

See [API/protocol](docs/api-v1.md) and [deployment/security](docs/hosting.md). Add registration rate limits and infrastructure quotas before exposing your deployment publicly. Never log tokens, cookies, report bodies or complete WebSocket frames.

## PacketEvents integration

The default `native` provider needs no PacketEvents. To use `channel-provider: "packetevents"`, install the external Velocity PacketEvents 2.14.0 plugin and restart the proxy. Exactly one discovery provider is selected. Both providers use the **same raw-byte Netty counter**.

PacketEvents is not bundled, globally reconfigured, terminated or deshaded by this profiler. Its events are not summed as transport bytes. Packet analysis and lightweight statistics work with either transport discovery provider and check our own client-channel marker to exclude backends. The supported observation boundary is validated specifically against PacketEvents 2.14.0 on Velocity 4.3.0; other PacketEvents versions are rejected until validated. Independent backend traffic, sending-plugin attribution and custom-payload channel breakdown are not implemented. No IP addresses are collected in attribution metadata.

## Architecture and performance

```text
profiler-api                 platform-free contracts
    ↑
profiler-core               LongAdder counters, immutable samples, sessions, ring buffer
    ↑                 ↑
profiler-netty         profiler-server (local services / files, no listener)
    ↑                 ↑
profiler-platforms/velocity → profiler-relay (outgoing Java HTTP/WS)
                                    ↓
                    profiler-backend (Hono: Node or Cloudflare DO)
                                    ↑
                    profiler-web (Astro + React dashboard)
```

No buffers are copied, retained or inspected for packet content by the counting handler. Per-event collection does not acquire global locks. Sampling happens on a separate scheduler. OUT uses promise completion listeners with size/session only. Shutdown removes our handlers/listeners and stops our schedulers/relay, never proxy-owned channels or loops.

A single permanent PacketEvents listener updates lightweight direction counters when statistics are enabled, without packet-type lookup. Active captures additionally use a bounded type catalog, dimension counters and per-thread stop gates, not packet objects. No JSON, disk IO, wrappers or buffer copies are created per observed packet by our listener. First-use dimension/thread bookkeeping and LongAdder contention cells can allocate; first-use dimension registration is synchronized, steady-state updates are not globally locked. PacketEvents itself has baseline processing/copying overhead. Relay serialization/RPC, checkpoints and final serialization stay off packet event loops. Relay queues, frames and report responses have explicit bounds; Cloudflare storage/event costs still depend on active sessions. No overhead percentage is promised without representative benchmarks.

See [instrumentation and limitations](docs/instrumentation.md). `TOTAL_TRAFFIC` is available for supported transport collection; `PACKET_TRAFFIC` is declared when capture analysis is available; `CONNECTION_TRAFFIC`, `PLAYER_TRAFFIC` and `SERVER_ATTRIBUTION` describe enabled, healthy protocol attribution. `BACKEND_TRAFFIC` is deliberately not declared. BungeeCord, Paper, Folia/Pinac, Fabric and NeoForge are architectural targets, **not supported integrations yet**.

Timeline/population aggregation also runs on the independent capture worker, not per packet. Disk delays can lengthen sampling intervals; rates use actual elapsed time without inventing missed one-second samples. Temporal state and persisted files have explicit bounds; the 32 MiB file limit is not a JVM memory/overhead guarantee.

## Development and tests

Build the static dashboard, then run the Node backend (which serves API, WebSockets and Astro assets on one origin):

```sh
cd profiler-web
npm ci
npm run build
cd ../profiler-backend
npm ci
PUBLIC_ORIGIN=http://127.0.0.1:3000 npm start
```

For this loopback setup use the same origin in the plugin's relay URLs. `npm run dev:cloudflare` runs the Durable Object adapter locally; see [hosting](docs/hosting.md) for its origin override. Astro dev alone is a UI shell, not a relay. Plugin-only packaging is independent: `./gradlew :profiler-platforms:velocity:shadowJar`.

```sh
./gradlew check
./gradlew :profiler-netty:test
./gradlew :profiler-core:benchmarkCapture
```

For the actual shaded JAR against the pinned Velocity distribution:

```sh
python3 tools/smoke_velocity.py \
  --velocity-jar /path/to/velocity-4.3.0-44.jar \
  --plugin profiler-platforms/velocity/build/libs/vegui-network-profiler-velocity-0.1.0.jar
```

The script starts an isolated Node relay and real loopback Velocity, registers the plugin, creates a browser session, verifies pending reads are forbidden, approves it through the console, then compares exact Minecraft status/ping IN/OUT totals. Add `--cloudflare` to use Wrangler locally, `--proxy-protocol`, or `--packetevents /path/to/packetevents-velocity-2.14.0.jar`. Add `--packet-capture` with PacketEvents for exact type counts, shared command/remote capture controls, immutable reports and report/Settings persistence across a real restart. It does not accept an EULA or start a full backend.

`tools/smoke_attribution.py` accepts the same three JAR arguments and exercises actual offline login, a successful lobby → survival transfer, scoped exclusion, physical disconnect finalization and player reconnect tracking against two minimal protocol backend peers. It does not start full Minecraft servers or accept an EULA; modern online-mode/gameplay compatibility still requires manual testing.

`benchmarkCapture` compares lightweight statistics alone with statistics + attributed capture, using one/eight threads. It is an indicative warmed core timing/allocation probe, not total PacketEvents/proxy overhead, TPS, UI/disk work or thousands of connections. Profile representative plugin combinations and gameplay workloads before making production performance claims.

Login, movement, encryption/compression and chunk transfer against a full backend require the additional manual scenarios in [the validation checklist](docs/validation.md). A successful status smoke test is not a substitute for those scenarios.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE). Frontend component patterns derived from shadcn/ui retain their MIT notice in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Dependencies retain their own licenses.
