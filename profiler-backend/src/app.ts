import { Hono, type Context } from 'hono'
import { Hub } from './hub.js'
import { Attachment, dashboardPattern, Fault, json, MAX_FRAME, uuidPattern, boundedBody } from './model.js'

export interface Runtime {
  origin: string
  register(): Promise<{ serverUuid: string; serverToken: string }>
  hub(uuid: string): Promise<Hub | null>
  upgrade(request: Request, hub: Hub, attachment: Attachment, context: Context): Promise<Response> | Response
  assets(request: Request): Promise<Response>
}
export function createApp(runtime: Runtime) {
  const app = new Hono()
  app.onError((e, c) => e instanceof Fault ? json({ error: e.message }, e.status) : json({ error: 'Relay operation failed' }, 500))
  app.use('/api/*', async (c, next) => {
    const length = Number(c.req.header('content-length') ?? 0)
    if (length > MAX_FRAME) return json({ error: 'Request too large' }, 413)
    await next()
  })
  app.post('/api/v1/server', async c => {
    // Deploy behind platform rate limiting; never log credentials or request/response bodies.
    await boundedBody(c.req.raw)
    return json(await runtime.register(), 201)
  })
  app.get('/api/v1/events/:uuid', async c => {
    if (!uuidPattern.test(c.req.param('uuid'))) throw new Fault(404, 'Unknown server')
    const hub = await runtime.hub(c.req.param('uuid')); if (!hub) throw new Fault(401, 'Invalid server credentials')
    await hub.authenticate(c.req.header('authorization')?.replace(/^Bearer /, '') ?? '')
    await hub.run(() => hub.sweep())
    if (c.req.header('upgrade')?.toLowerCase() !== 'websocket') throw new Fault(426, 'WebSocket required')
    return runtime.upgrade(c.req.raw, hub, { role: 'server' }, c)
  })
  app.all('/api/v1/dashboard/:dashboard/*', async c => {
    const id = c.req.param('dashboard'), match = dashboardPattern.exec(id)
    if (!match || !uuidPattern.test(match[1])) throw new Fault(410, 'Unknown dashboard')
    const hub = await runtime.hub(match[1]); if (!hub) throw new Fault(410, 'Unknown dashboard')
    await hub.run(() => hub.sweep())
    const suffix = new URL(c.req.url).pathname.substring(`/api/v1/dashboard/${id}`.length)
    if (suffix === '/viewer' && c.req.method === 'POST') return hub.run(() => hub.join(c.req.raw, id))
    if (suffix === '/session' && c.req.method === 'GET') {
      const v = await hub.viewer(c.req.raw, id, false); return json(hub.session(hub.dashboard(id), v))
    }
    if (suffix === '/events' && c.req.method === 'GET') {
      hub.originCheck(c.req.raw)
      const v = await hub.viewer(c.req.raw, id, false)
      return runtime.upgrade(c.req.raw, hub, { role: 'viewer', dashboardId: id, viewerId: v.id }, c)
    }
    if (suffix.startsWith('/proxy/')) return hub.request(c.req.raw, id, '/api/v1' + suffix.substring(6) + new URL(c.req.url).search)
    throw new Fault(404, 'Unknown operation')
  })
  app.all('/api/*', () => json({ error: 'Not found' }, 404))
  app.get('*', c => runtime.assets(c.req.raw))
  return app
}
