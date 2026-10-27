# Validation checklist

## Automated build

Run `./gradlew clean build` with JDK 21, JDK 25 and Node 24.15+. This runs core/concurrency/history/session tests, Netty EmbeddedChannel accounting tests, HTTP/WebSocket integration tests, configuration tests, frontend unit/component tests, TypeScript checking and production packaging.

## Real proxy smoke

`tools/smoke_velocity.py` starts the actual pinned Velocity distribution with the shaded plugin, makes twelve real Minecraft status/ping exchanges and compares the sent/received byte counts exactly against the API counters. It also checks static dashboard resources, history, proxy exit and HTTP port closure. Run both discovery providers with and without PROXY protocol. With external PacketEvents, add `--packet-capture` to verify exact per-type counts/bytes, HTTP/command control of the same capture, immutable local reports, and persistence across a real proxy restart. Also run without PacketEvents to check standalone transport collection.

The smoke creates isolated files under `/tmp/opencode` and preserves logs for inspection. It never opens public listeners, accepts an EULA or starts a backend. It requires the locally supplied Velocity distribution with the pinned checksum. `--hold-seconds` keeps generating real status traffic for browser inspection before automatic shutdown.

### Recorded validation — 2026-10-27

`./gradlew clean build --no-daemon` passed on Linux with JDK 21/25 and Node 24.21.0; the final `./gradlew build --no-daemon` passed **56 Java and 20 frontend tests**. The suite covers capture concurrency/freeze/session isolation, scoped collection and reconnect identity, historical association, attribution/registry limits, actual-elapsed statistics rates, legacy/schema-3 persistence, full-denominator pagination/search, duration/retention, source failure, asynchronous slow/failing storage, checkpoint recovery, CSRF/Origin checks, shared HTTP/command capture state, and native initializer cleanup in both PacketEvents shutdown orders. Lost identity explicitly marks a scoped report incomplete, rather than silently excluding possibly matching traffic. The shaded JAR contains the dashboard, has no duplicate archive paths, and does not bundle host Netty, Velocity API, SLF4J or PacketEvents classes. The artifact was tested on Velocity 4.3.0 build 44, with external PacketEvents 2.14.0 in all four capture variants:

| Discovery provider | PROXY protocol | Transport IN | Transport OUT | Packet report | Result |
| --- | --- | --- | --- | --- | --- |
| Native | Off | 348 B | 2,136 B | 60 packets / 2,412 B | Exact match; restart persistence; clean shutdown |
| Native | On | 876 B | 2,136 B | 60 packets / 2,412 B | Exact match; restart persistence; clean shutdown |
| PacketEvents | Off | 348 B | 2,136 B | 60 packets / 2,412 B | Exact match; restart persistence; clean shutdown |
| PacketEvents | On | 876 B | 2,136 B | 60 packets / 2,412 B | Exact match; restart persistence; clean shutdown |

Every packet report matched five logical types exactly: HANDSHAKE, REQUEST, PING, RESPONSE and PONG, with 312 B inbound and 2,100 B outbound protocol observations and no observation errors or cancellations. Transport totals include framing and, when enabled, PROXY headers; packet totals do not. Reports started via HTTP were stopped by the real `/mnp stop` console command, and a second report was started by `/mnp start 30` and stopped via HTTP. Both reports remained unchanged after further traffic and a real Velocity restart. A separate run without PacketEvents also passed transport accounting and clean shutdown. There was no backend traffic to include in these tests.

After a reported `Can't unwrap foreign channel initializer` shutdown warning, the native wrapper was changed to expose its mutable delegate to PacketEvents. Regression tests use PacketEvents' actual field-lookup helper and ensure shutdown never resurrects its already-removed initializer or overwrites a later plugin. All four real capture variants and the standalone run passed again; the smoke now explicitly rejects PacketEvents uninject warnings instead of relying only on process exit. Modded registry failures in PacketEvents' own `InternalPacketListener` are a separate unresolved compatibility limitation; see [instrumentation](instrumentation.md#modded-registry-compatibility).

Browser checks against the real embedded dashboard verified a connected stream, growing totals, two chart series, HTTP history, bytes/bits, dark/light, individual series hiding and pause/resume while API sequences continued increasing. At 390 px viewport width, the page had no horizontal overflow. These checks do not establish full gameplay compatibility or performance at scale. Windows/macOS runtime/build validation is not yet recorded.

Packet-report browser checks used actual status traffic: Start analysis opened a live report; packet bytes/counts grew, direction tabs changed the percentage denominator, and Stop & save report produced an immutable completed report. Direct report URL refresh worked. At 390 px width, the table scrolled inside its card without horizontal page overflow. No browser console errors or warnings were observed during these checks.

### Association and scoped-capture smoke

`tools/smoke_attribution.py` uses the same actual Velocity and PacketEvents distributions, with two minimal Python TCP backend protocol peers and offline protocol-47 clients Alice/Bob. These are test peers, **not full Minecraft server distributions**; no EULA is accepted. The test shortens statistics publication to one second only in its isolated configuration (production default remains 30 seconds).

Recorded PASS on the final artifact, log `/tmp/opencode/velocity-attribution-alfgpyjo/smoke.log`:

- Actual login associates the client connection/player with lobby.
- The real `/server survival` command completes a transfer. A PLAYER capture retains lobby and survival observations under the same physical ID/UUID without moving earlier bytes.
- A SERVER survival capture excludes Bob's lobby traffic.
- A CONNECTION capture completes when Alice physically disconnects.
- A PLAYER Bob capture survives disconnect/reconnect and contains two different physical IDs under one UUID.
- Published server counters contain real observed bytes, complete coverage and zero observation errors. Completed historic reports remain unchanged; shutdown closes the HTTP port and has no PacketEvents uninject warning.

Browser checks on a live attribution smoke exercised Servers → Details → Start analysis (confirmed SERVER scope), Stop & save, direct report refresh, historical lobby/survival shares (85 B / 79 B in that run), server filtering that updates packet totals, and the 390 px layout without horizontal page overflow. No console errors/warnings were observed during the completed checks. Active/recent connection states are published snapshots, not instantaneous UI presence.

### Timeline, population and shared settings

Additional tests cover real elapsed/final partial intervals, idle zeros, weighted means, min/max and filtering, history budgets with explicit gaps, immutable transfer populations, schema-1/2 absence of history, schema-3 checkpoint recovery, settings validation/atomic persistence/failed-save preservation, CSRF/foreign Origin rejection, and Mbps-to-byte reference conversion without graph clipping. A Velocity adapter test verifies pre-login tracked sockets do not increase player counts and that sampled current-server maps follow transfers/disconnects independently of historical byte attribution.

The updated real attribution smoke passed with log `/tmp/opencode/velocity-attribution-cb3o427x/smoke.log`: schema-3 timeline deltas sum to final capture bytes; Alice's local population follows lobby → survival; global counts grow from one to two real logged-in test clients; server scope observes one player per backend; Overview count falls after disconnect. Saving independent 100/50 Mbps references produces matching API and atomic disk contents.

All four real status/capture variants passed again with schema-3 delta reconciliation, zero players for status-only sockets, immutable report timelines and 100/50 settings surviving actual proxy restart. Logs: native `/tmp/opencode/velocity-profiler-ex8mvxit/smoke.log`, native + PROXY `velocity-profiler-bdbvu6l0/smoke.log`, PacketEvents `velocity-profiler-gajo9sx8/smoke.log`, PacketEvents + PROXY `velocity-profiler-rso19tkl/smoke.log` (all under `/tmp/opencode`). Standalone without PacketEvents also passed (`velocity-profiler-t1qt4faa/smoke.log`). All shut down cleanly without PacketEvents uninject warnings.

Browser checks against that actual proxy verified four graph series on a saved PLAYER report, historical server filtering that changes curves/totals/statistics together (164 B → 79 B in this run), independent global/local populations, and live one-second rates. Settings loaded the saved 100/50 values and successfully disabled both references through the UI/API. At 390 px width there was no horizontal page overflow. A final live stop click overlapped the smoke's timed shutdown and produced expected connection-refused errors; it is not claimed as a successful browser stop for this run. API/command finalization is separately tested.

### Limited core hot-path probe

`./gradlew :profiler-core:benchmarkCapture` performs 200,000 warm-up observations followed by 1,000,000 measured observations per thread. It compares permanent statistics alone with statistics plus an attributed capture. A recorded run reported 12.98 / 19.75 ns per observation for statistics-only (one/eight threads) and 64.23 / 128.07 ns for statistics + capture; measured allocation rounded to 0.0000–0.0001 B per observation. A subsequent run alongside the clean build reported 46.92 / 70.15 ns and 96.29 / 260.84 ns respectively, with 0.0000–0.0003 B allocation. The variation illustrates why these are diagnostics, not overhead guarantees. Final capture counters included all 1.2 million / 9.6 million warm-up and measured observations.

This is a warmed, synthetic **core-only diagnostic**, not a statistically rigorous end-to-end benchmark. It excludes PacketEvents event/buffer costs, pipeline lookup, persistence and the dashboard, and does not establish TPS impact or production overhead. PacketEvents has its own baseline overhead even while this profiler is not capturing. Repeat with representative gameplay, plugin combinations and connection counts before making scalability claims.

## Full gameplay acceptance — separate manual requirement

Use a permitted Minecraft backend and an actual client; status/ping cannot establish these results:

- [ ] Login completes with online-mode encryption.
- [ ] Movement and chunk loading increase both totals and produce live rates.
- [ ] Compression enabled/disabled does not remove or duplicate the transport counter.
- [ ] Backend transfers do not add a second copy of client traffic.
- [ ] Disconnect/reconnect cleans up handlers and preserves session totals.
- [ ] Reset changes session/history while pending writes cannot contaminate the new session.
- [ ] Dashboard reconnects after proxy restart and discards the previous session.
- [ ] A slow dashboard cannot cause unlimited pending messages.
- [ ] Shutdown leaves no profiler threads or listening socket.
- [ ] Required plugin combinations preserve the initializer and pipeline position.
- [ ] Packet capture during login/configuration/play covers the expected real packet types with encryption/compression and the deployed translation/modification plugins.
- [ ] Real modern backends: successful, cancelled and failed transfers preserve the expected association around configuration/JoinGame transitions.
- [ ] Representative hundreds/thousands of connections validate configured bounds, observation overhead and dashboard/report usability.

Do not mark unexecuted scenarios as passed. No overhead percentage or thousand-connection performance claim is made without representative benchmarks.
