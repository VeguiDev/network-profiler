import { serve, upgradeWebSocket } from '@hono/node-server'
import { WebSocketServer } from 'ws'
import { mkdir, readFile, rename, writeFile, stat, readdir } from 'node:fs/promises'
import { resolve, extname, sep } from 'node:path'
import { createApp } from './app.js'
import { Hub } from './hub.js'
import { Attachment, hash, json, random, Socket, State, validateOrigin } from './model.js'

const origin = validateOrigin(process.env.PUBLIC_ORIGIN ?? 'http://localhost:3000')
const directory = resolve(process.env.DATA_DIRECTORY ?? './data'), assets = resolve(process.env.WEB_DIRECTORY ?? '../profiler-web/dist')
await mkdir(directory, { recursive: true, mode: 0o700 })
const hubs = new Map<string, Hub>()
const loading = new Map<string, Promise<Hub | null>>()
async function load(uuid: string) {
  if (hubs.has(uuid)) return hubs.get(uuid)!
  if (loading.has(uuid)) return loading.get(uuid)!
  const result = readHub(uuid).finally(() => loading.delete(uuid)); loading.set(uuid, result); return result
}
async function readHub(uuid: string) {
  try {
    const state = JSON.parse(await readFile(resolve(directory, `${uuid}.json`), 'utf8')) as State
    const hub = makeHub(state); hubs.set(uuid, hub); return hub
  } catch (e) { if ((e as NodeJS.ErrnoException).code === 'ENOENT') return null; throw e }
}
function makeHub(state: State) {
  return new Hub(state, {
    async save(next) { const file = resolve(directory, `${state.serverUuid}.json`), tmp = `${file}.tmp`; await writeFile(tmp, JSON.stringify(next), { mode: 0o600 }); await rename(tmp, file) },
    async alarm() { /* single-process sweeper; durable timestamps remain authoritative after restart */ },
  }, origin)
}
const wss = new WebSocketServer({ noServer: true, maxPayload: 96 * 1024, perMessageDeflate: false })
const app = createApp({
  origin,
  async register() {
    const serverUuid = crypto.randomUUID(), serverToken = random(32)
    const hub = makeHub({ serverUuid, tokenHash: await hash(serverToken), serverLease: 0, bootId: null, dashboards: {}, boots: [] })
    await hub.persist(); hubs.set(serverUuid, hub); return { serverUuid, serverToken }
  },
  hub: load,
  upgrade(request, hub, attachment, context) {
    let socket: Socket
    return upgradeWebSocket(() => ({
      onOpen(_event, ws) {
        socket = { attachment, send(data) { if ((ws.raw as import('ws').WebSocket).bufferedAmount > 1024 * 1024) throw new Error('Slow client'); ws.send(data) }, close(code, reason) { ws.close(code, reason) } }
        void hub.run(() => hub.attach(socket)).catch(() => ws.close(1011, 'Relay error'))
      },
      onMessage(event, ws) { if (typeof event.data !== 'string') { ws.close(1003, 'Text only'); return }; void hub.run(() => hub.message(socket, event.data as string)).catch(() => ws.close(1008, 'Invalid operation')) },
      onClose() { if (socket) void hub.run(() => hub.detach(socket)) },
      onError() { if (socket) void hub.run(() => hub.detach(socket)) },
    }))(context, async () => {}) as Promise<Response>
  },
  async assets(request) {
    const pathname = decodeURIComponent(new URL(request.url).pathname)
    const file = resolve(assets, `.${pathname}`)
    if (!file.startsWith(assets + sep) && file !== assets) return json({ error: 'Not found' }, 404)
    let target = file
    try { if (!(await stat(file)).isFile()) target = resolve(assets, 'index.html') } catch { target = resolve(assets, 'index.html') }
    try { return new Response(await readFile(target), { headers: { 'content-type': ({ '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.png': 'image/png', '.ico': 'image/x-icon' } as Record<string, string>)[extname(target)] ?? 'application/octet-stream', 'cache-control': target.endsWith('index.html') ? 'no-store' : 'public, max-age=86400' } }) }
    catch { return json({ error: 'Build profiler-web first' }, 503) }
  },
})
const server = serve({ fetch: app.fetch, hostname: process.env.HOST ?? '127.0.0.1', port: Number(process.env.PORT ?? 3000), websocket: { server: wss } })
console.info(`Profiler relay listening on ${origin}`)
const sweep = setInterval(() => { for (const hub of hubs.values()) void hub.run(() => hub.sweep()).catch(() => console.error('Relay persistence failure')) }, 10_000)
// Load persisted shards on restart so abandoned temporary samples expire without a new HTTP visit.
for (const entry of await readdir(directory, { withFileTypes: true })) if (entry.isFile() && /^[a-f0-9-]{36}\.json$/.test(entry.name)) {
  const hub = await load(entry.name.slice(0, -5)); if (hub) await hub.run(() => hub.sweep())
}
for (const signal of ['SIGINT', 'SIGTERM'] as const) process.on(signal, () => { clearInterval(sweep); for (const ws of wss.clients) ws.close(1001, 'Relay restarting'); server.close(() => process.exit(0)) })
