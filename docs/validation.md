# Validation

## Automated checks

Run `./gradlew clean build --no-daemon` with JDK 21, JDK 25 and Node 24.15+. This builds the Velocity JAR, Astro static dashboard and Hono backend independently. It runs Java core/Netty/service/relay/configuration tests, frontend tests, backend security tests and TypeScript checks. `npm run check:cloudflare` from the repository root additionally validates Workers/Durable Objects packaging without deploying, using the root `wrangler.jsonc` and the Wrangler version pinned in `profiler-backend`.

For the hosted apps alone, run `npm run build:cloudflare` from the repository root. This performs locked dependency installation, Astro build, Hono type-checking and Wrangler dry-run packaging without requiring Java or deploying. After installation, root `npm test` runs frontend/backend tests. `npm run deploy:cloudflare` is deliberately separate and must not be run as a validation step. The root commands passed locally: installs completed, Astro emitted the static dashboard, Hono type-checking passed, Wrangler 4.153.0 dry-run packaged the Worker, Durable Object and six assets, and `npm test` passed 25 frontend and 11 backend tests. npm reported non-blocking `allowScripts` warnings for esbuild/workerd install hooks; both tools worked without approving them.

Coverage includes:

The build-time-origin `./gradlew clean build --no-daemon` passed 54 Java tests; the subsequent release-workflow `./gradlew build --no-daemon` passed **55 Java, 25 frontend and 11 backend tests**. Cloudflare dry-run packaging also passed. The approximately 3.21 MB plugin JAR contains neither frontend resources nor Javalin/Jetty/servlet classes, and has no duplicate archive paths or bundled host APIs.

- Transport accounting, session/reset isolation, capture concurrency/freeze, scoped attribution, transfers/reconnects, immutable reports and bounded schema-3 history.
- Local service operation allowlisting, scope validation, full-denominator statistics pagination, settings validation/atomic persistence and failed-save preservation.
- Private relay identity persistence, endpoint binding, invalid configuration and relay failures that do not stop local collection. Relay is opt-out; URLs are embedded at build time, and runtime YAML URL keys are rejected.
- Unapproved browsers cannot read metrics; link possession does not authorize another browser. Approval codes are single-use and isolated by server.
- Exact 40-minute expiry per viewer, reconnection only cancelling grace when both peers return, independent viewers, pending-code expiry, durable-state reconstruction and temporary-history cleanup.
- Offline cached transport history without invented zeros, separate boot series, bounded chunked report responses, write failure without replay, Origin/CSRF rejection and bounded request bodies.
- Astro approval gate, scoped deep links/API paths, remote CSRF, offline sample retention, boot changes clearing previous presentation, chart references and unknown relay gaps.

Release automation additionally passed Actionlint and three Python version-resolution tests, including malformed/injection-like tags. Local builds for `0.2.0-rc.1` and the default `0.1.0` both passed adapter tests and packaging; each JAR's filename and generated Velocity descriptor matched the requested version, and both retained the official embedded relay origins. The default artifact was restored afterward. No GitHub Release was published during these checks.

## Recorded hosted-runtime checks — 2026-10-27

The updated smoke scripts start isolated loopback Node or local Wrangler runtimes and the actual pinned Velocity 4.3.0 build 44 distribution. They do not deploy to Cloudflare or modify production. External PacketEvents 2.14.0 is supplied separately when capture tests are requested.

`tools/smoke_velocity.py` verifies registration, the outgoing authenticated server WebSocket, `/vnp dashboard`, browser-specific `/vnp trustSession`, rejection before approval and allowlisted RPC. Twelve real status/ping exchanges match **348 B transport IN / 2,136 B OUT**. Packet analysis matches **60 packets / 2,412 protocol bytes across five types**, with no observation errors or cancellations. Captures are started/stopped across console and remote API, final interval deltas reconcile to totals, reports remain immutable, and reports plus 100/50 Mbps settings survive an actual Velocity restart. After Minecraft disconnects, session metadata reports offline and the approved viewer can still read cached temporary history. Shutdown rejects PacketEvents uninject warnings.

Recorded complete passes:

| Hosted runtime | Transport provider | Log |
| --- | --- | --- |
| Node | Native + external PacketEvents analysis | `/tmp/opencode/velocity-profiler-vykvq2wc/smoke.log` |
| Local Wrangler / Durable Objects | Native + external PacketEvents analysis | `/tmp/opencode/velocity-profiler-33rcbo2_/smoke.log` |

After the complete VNP rename, the clean build and Cloudflare dry-run passed again. The Node/Velocity capture-and-restart smoke also passed (`/tmp/opencode/velocity-profiler-qms_1jlw/smoke.log`), exercising plugin ID `vnp`, `plugins/vnp`, `/vnp` commands and the renamed authentication headers. The packaged entry point is `dev.vegui.vnp.velocity.VeguiNetworkProfilerPlugin`.

`tools/smoke_attribution.py` also passed through the Node relay (`/tmp/opencode/velocity-attribution-112cga41/smoke.log`). It uses two minimal Python TCP backend protocol peers and offline protocol-47 clients Alice/Bob, **not full Minecraft server distributions**. It verifies lobby login, real `/server survival` transfer, historical attribution, SERVER exclusion, physical CONNECTION auto-stop, PLAYER capture across two reconnect IDs, global/local real player counts, saved timelines and settings. No EULA is accepted. Statistics use a one-second interval only in this isolated test configuration; the production default remains 30 seconds.

Useful commands (supply your own pinned distribution files):

```sh
VNP_RELAY_URL=http://127.0.0.1:18765 \
  ./gradlew :profiler-platforms:velocity:shadowJar --no-daemon
python3 tools/smoke_velocity.py --velocity-jar /path/velocity.jar --plugin profiler-platforms/velocity/build/libs/vegui-network-profiler-velocity-0.1.0.jar --packetevents /path/packetevents.jar --packet-capture
# Add --cloudflare for local Wrangler, --proxy-protocol for PROXY headers,
# or --channel-provider packetevents to exercise alternate discovery.
python3 tools/smoke_attribution.py --velocity-jar /path/velocity.jar --plugin profiler-platforms/velocity/build/libs/vegui-network-profiler-velocity-0.1.0.jar --packetevents /path/packetevents.jar
```

The scripts read their listen origin from the plugin's embedded build properties. They require an HTTP `127.0.0.1` origin with an explicit free port and the same public web origin; remote/official-origin JARs are refused before launching a backend. Runtime environment variables do not change that JAR. Rebuild without the URL variables to restore the official-origin artifact after testing.

The build-time-origin smoke passed (`/tmp/opencode/velocity-profiler-06tj9dq0/smoke.log`) using a JAR compiled for loopback port 18765 while the running Java process had both URL variables deliberately set to port 1. Registration, browser approval, capture and report/settings persistence across an actual Velocity restart still used the embedded port. Separate generator checks rejected a non-TLS remote origin without changing the previous resource, accepted independent relay/web build variables, and restored the official origin incrementally. The final JAR embeds `https://networkprofiler.vegui.dev` for both URLs; the Cloudflare dry-run validates that origin and the configured account/custom domain without deploying.

Earlier embedded-HTTP releases passed both discovery providers with/without PROXY protocol and standalone transport without PacketEvents. Those results are historical, not a claim that every combination has been repeated for the new hosted architecture. The former embedded HTTP/WebSocket tests were replaced by local service tests, relay authorization/state tests and the real Node/Wrangler integration runs above.

### Browser acceptance

Against the final JAR, Node relay and Astro build, an independent browser initially saw only the approval gate. Real `/vnp trustSession` approval opened Overview automatically. Starting and stopping a capture through the browser produced a completed 30-packet, 1,206-byte report with saved timeline. Direct Settings navigation and saving separate 100/50 Mbps references succeeded.

Stopping only Minecraft left the hosted dashboard running: it showed the disconnected warning and expiry, retained 93 measured samples without inventing zeros, disabled capture mutations, and kept graph controls usable. Reloading the page while Minecraft remained offline recovered the same temporary history from the backend using the approved browser cookie. At 390 px width there was no horizontal page overflow. Local service requests correctly returned 503 while offline; this is not a zero-console-error claim. Isolated logs: `/tmp/opencode/remote-browser-_b459kwe/`. All test harness processes were stopped afterward.

## Performance boundaries

`./gradlew :profiler-core:benchmarkCapture` is an optional warmed synthetic core-only probe. Previous runs varied substantially under concurrent build load. It excludes PacketEvents buffers/events, relay persistence/egress, full gameplay and the UI; no TPS/overhead or thousand-connection guarantee follows from it. Cloudflare heartbeats and retained samples incur operations and storage writes: measure real deployment cost and load, apply registration rate limits/quotas, and test slow-client behavior.

## Separate manual acceptance

- [ ] Modern online-mode encryption, compression, chunks and full gameplay with actual clients/backends.
- [ ] Required modded/translation/plugin combinations; PacketEvents registry limitations remain unresolved.
- [ ] Successful, cancelled and failed modern backend transfers around configuration/JoinGame transitions.
- [ ] Representative hundreds/thousands of connections, report sizes and slow browsers.
- [ ] Real Cloudflare deployment, hibernation/eviction and network-failure behavior at production scale (local Wrangler is not that guarantee).
- [ ] Windows/macOS runtime and build verification.

Do not mark unexecuted scenarios as passed. Definitive reports/settings remain on Minecraft; backend retention tests cover only bounded temporary statistics and authorization metadata.
