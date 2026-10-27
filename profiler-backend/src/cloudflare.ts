import { DurableObject } from 'cloudflare:workers'
import { Hono } from 'hono'
import { createApp } from './app.js'
import { Hub } from './hub.js'
import { Attachment, hash, json, random, Socket, State, uuidPattern, dashboardPattern, validateOrigin, boundedBody } from './model.js'

interface Env { SERVERS: DurableObjectNamespace<ServerRelay>; ASSETS: Fetcher; PUBLIC_ORIGIN: string }
export class ServerRelay extends DurableObject<Env> {
  private hub: Hub | null = null
  private wrappers = new Map<WebSocket, Socket>()
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env)
    ctx.blockConcurrencyWhile(async () => {
      const count = await ctx.storage.get<number>('parts')
      if (count) {
        let text = ''; for (let n = 0; n < count; n++) text += await ctx.storage.get<string>(`state:${n}`) ?? ''
        this.hub = this.makeHub(JSON.parse(text))
        for (const ws of ctx.getWebSockets()) { const attachment = ws.deserializeAttachment() as Attachment; const wrapper = this.wrap(ws, attachment); this.hub.sockets.add(wrapper) }
      }
    })
  }
  private wrap(ws: WebSocket, attachment: Attachment): Socket {
    const socket: Socket = { attachment, send(data) { ws.serializeAttachment(attachment); ws.send(data) }, close(code, reason) { ws.close(code, reason) } }
    this.wrappers.set(ws, socket); return socket
  }
  private makeHub(state: State) {
    return new Hub(state, {
      save: async next => {
        const text = JSON.stringify(next), size = 24000, count = Math.ceil(text.length / size)
        await this.ctx.storage.transaction(async tx => {
          const old = await tx.get<number>('parts') ?? 0
          for (let n = 0; n < count; n++) await tx.put(`state:${n}`, text.slice(n * size, (n + 1) * size))
          for (let n = count; n < old; n++) await tx.delete(`state:${n}`)
          await tx.put('parts', count)
        })
      },
      alarm: async timestamp => { if (timestamp === null) await this.ctx.storage.deleteAlarm(); else await this.ctx.storage.setAlarm(timestamp) },
    }, validateOrigin(this.env.PUBLIC_ORIGIN))
  }
  async fetch(request: Request): Promise<Response> {
    if (new URL(request.url).pathname === '/internal/register') {
      if (this.hub) return json({ error: 'Already registered' }, 409)
      const input = await request.json<{ serverUuid: string; tokenHash: string }>()
      this.hub = this.makeHub({ ...input, bootId: null, serverLease: 0, dashboards: {}, boots: [] }); await this.hub.persist(); return json({ registered: true }, 201)
    }
    const app = createApp({
      origin: this.env.PUBLIC_ORIGIN,
      register: async () => { throw new Error('Wrong registration route') },
      hub: async () => this.hub,
      upgrade: (_request, hub, attachment) => {
        const pair = new WebSocketPair(), [client, server] = Object.values(pair)
        this.ctx.acceptWebSocket(server); server.serializeAttachment(attachment)
        const socket = this.wrap(server, attachment)
        return hub.run(async () => { await hub.attach(socket); return new Response(null, { status: 101, webSocket: client }) })
      },
      assets: async () => json({ error: 'Not found' }, 404),
    })
    return app.fetch(request)
  }
  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer) {
    const socket = this.wrappers.get(ws)
    if (!socket || !this.hub || typeof message !== 'string') { ws.close(1003, 'Text only'); return }
    try { await this.hub.run(() => this.hub!.message(socket, message)); ws.serializeAttachment(socket.attachment) } catch { ws.close(1008, 'Invalid operation'); await this.hub.run(() => this.hub!.detach(socket)) }
  }
  async webSocketClose(ws: WebSocket) { const socket = this.wrappers.get(ws); if (socket && this.hub) await this.hub.run(() => this.hub!.detach(socket)); this.wrappers.delete(ws) }
  async webSocketError(ws: WebSocket) { await this.webSocketClose(ws) }
  async alarm() { if (this.hub) await this.hub.run(() => this.hub!.sweep()) }
}
const app = new Hono<{ Bindings: Env }>()
app.post('/api/v1/server', async c => {
  validateOrigin(c.env.PUBLIC_ORIGIN)
  await boundedBody(c.req.raw)
  const serverUuid = crypto.randomUUID(), serverToken = random(32), stub = c.env.SERVERS.get(c.env.SERVERS.idFromName(serverUuid))
  const response = await stub.fetch('https://internal/internal/register', { method: 'POST', body: JSON.stringify({ serverUuid, tokenHash: await hash(serverToken) }) })
  return response.ok ? json({ serverUuid, serverToken }, 201) : json({ error: 'Registration failed' }, 503)
})
app.all('/api/*', c => {
  const path = c.req.path, uuid = path.startsWith('/api/v1/events/') ? path.split('/')[4] : dashboardPattern.exec(path.split('/')[4] ?? '')?.[1]
  if (!uuid || !uuidPattern.test(uuid)) return json({ error: 'Not found' }, 404)
  return c.env.SERVERS.get(c.env.SERVERS.idFromName(uuid)).fetch(c.req.raw)
})
app.get('*', c => c.env.ASSETS.fetch(c.req.raw))
export default app
