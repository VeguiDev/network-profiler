import { Attachment, Boot, Dashboard, Fault, GRACE, json, LEASE, MAX_FRAME, MAX_RESPONSE, operationAllowed, PENDING, Persistence, random, hash, Socket, State, uuidPattern, Viewer, boundedBody } from './model.js'

/** One logical server shard. Adapters serialize calls and persist state before granting access. */
export class Hub {
  readonly sockets = new Set<Socket>()
  private pending = new Map<string, { socket: Socket; dashboardId: string; viewerId: string; resolve: (r: Response) => void; timer: ReturnType<typeof setTimeout>; chunks: Uint8Array[]; bytes: number; index: number; status: number }>()
  private queue: Promise<unknown> = Promise.resolve()
  constructor(public state: State, private store: Persistence, private origin: string, private clock = Date.now) {}
  run<T>(work: () => Promise<T>): Promise<T> {
    const result = this.queue.then(work); this.queue = result.catch(() => {}); return result
  }
  now() { return this.clock() }
  online() { return this.state.serverLease > this.now() && [...this.sockets].some(s => s.attachment.role === 'server') }
  private send(socket: Socket, body: unknown) { try { socket.send(JSON.stringify(body)) } catch { socket.close(1011, 'Send failed') } }
  private server() { return [...this.sockets].find(s => s.attachment.role === 'server') }
  private viewerOnline(id: string) { return [...this.sockets].some(s => s.attachment.role === 'viewer' && s.attachment.viewerId === id) }
  async persist() {
    await this.store.save(this.state)
    const deadlines: number[] = []
    if (this.state.serverLease) deadlines.push(this.state.serverLease)
    for (const d of Object.values(this.state.dashboards)) {
      if (d.emptySince !== null) deadlines.push(d.emptySince + GRACE)
      for (const v of Object.values(d.viewers)) {
        if (v.lease) deadlines.push(v.lease)
        if (!v.approved) deadlines.push(v.created + PENDING)
        if (v.missingSince !== null) deadlines.push(v.missingSince + GRACE)
      }
    }
    await this.store.alarm(deadlines.length ? Math.max(this.now() + 1000, Math.min(...deadlines)) : null)
  }
  async sweep() {
    const now = this.now()
    let changed = false
    if (this.state.serverLease && this.state.serverLease <= now) {
      changed = true
      const since = this.state.serverLease; this.state.serverLease = 0
      const s = this.server(); if (s) { this.sockets.delete(s); s.close(1001, 'Heartbeat expired'); this.failRequests(s) }
      for (const d of Object.values(this.state.dashboards)) for (const v of Object.values(d.viewers)) v.missingSince ??= since
    }
    for (const d of Object.values(this.state.dashboards)) {
      const hadViewers = Object.keys(d.viewers).length > 0
      for (const [id, v] of Object.entries(d.viewers)) {
        if (v.lease && v.lease <= now) {
          changed = true
          v.missingSince ??= v.lease; v.lease = 0
          for (const s of this.sockets) if (s.attachment.role === 'viewer' && s.attachment.viewerId === id) { this.sockets.delete(s); s.close(1001, 'Heartbeat expired') }
        }
        if ((!v.approved && now >= v.created + PENDING) || v.missingSince !== null && now >= v.missingSince + GRACE) {
          changed = true
          delete d.viewers[id]
          for (const s of this.sockets) if (s.attachment.role === 'viewer' && s.attachment.viewerId === id) { this.sockets.delete(s); s.close(4001, 'Session expired') }
        }
      }
      if (hadViewers && !Object.keys(d.viewers).length) { changed = true; delete this.state.dashboards[d.id]; continue }
      if (d.emptySince !== null && now >= d.emptySince + GRACE) { changed = true; delete this.state.dashboards[d.id] }
    }
    if (!Object.keys(this.state.dashboards).length && this.state.boots.length) { changed = true; this.state.boots = [] }
    for (const [id, p] of this.pending) if (!this.state.dashboards[p.dashboardId]?.viewers[p.viewerId]?.approved) {
      clearTimeout(p.timer); this.pending.delete(id); p.resolve(json({ error: 'Browser session expired' }, 410))
    }
    if (!changed) return
    this.subscription()
    await this.persist()
    this.broadcastState()
  }
  async authenticate(token: string) {
    if (!token || token.length > 256 || await hash(token) !== this.state.tokenHash) throw new Fault(401, 'Invalid server credentials')
  }
  originCheck(request: Request) {
    if (request.headers.get('origin') !== this.origin) throw new Fault(403, 'Origin rejected')
  }
  dashboard(id: string): Dashboard {
    const d = this.state.dashboards[id]; if (!d) throw new Fault(410, 'Dashboard expired or unknown'); return d
  }
  private cookieName(id: string) { return `vnp_${id.split('.')[1]}` }
  async viewer(request: Request, id: string, approved = true): Promise<Viewer> {
    const cookie = request.headers.get('cookie')?.split(';').map(c => c.trim()).find(c => c.startsWith(`${this.cookieName(id)}=`))?.split('=')[1]
    if (!cookie || cookie.length > 256) throw new Fault(401, 'Browser approval required')
    const digest = await hash(cookie), d = this.dashboard(id)
    const v = Object.values(d.viewers).find(v => v.credentialHash === digest)
    if (!v) throw new Fault(410, 'Browser session expired')
    if (approved && !v.approved) throw new Fault(403, 'Browser approval required')
    return v
  }
  async join(request: Request, id: string) {
    this.originCheck(request); const d = this.dashboard(id)
    try { const v = await this.viewer(request, id, false); return json(this.session(d, v)) } catch (e) { if (!(e instanceof Fault) || ![401, 410].includes(e.status)) throw e }
    if (Object.values(this.state.dashboards).reduce((n, d) => n + Object.keys(d.viewers).length, 0) >= 128) throw new Fault(429, 'Viewer capacity reached')
    const credential = random(32), now = this.now()
    const v: Viewer = { id: crypto.randomUUID(), credentialHash: await hash(credential), csrf: random(), code: random(8), approved: false, created: now, lease: 0, missingSince: now }
    d.viewers[v.id] = v; d.emptySince = null; await this.persist()
    const secure = this.origin.startsWith('https:') ? '; Secure' : '' // HTTP allowed only for explicit localhost development.
    return json(this.session(d, v), 201, { 'set-cookie': `${this.cookieName(id)}=${credential}; HttpOnly; SameSite=Strict; Path=/; Max-Age=86400${secure}` })
  }
  session(d: Dashboard, v: Viewer) {
    return { dashboardId: d.id, viewerSessionId: v.id, state: v.approved ? 'APPROVED' : 'PENDING', approvalCode: v.approved ? null : v.code, csrfToken: v.csrf,
      serverConnected: this.online(), bootId: this.state.bootId, expiresAt: v.missingSince === null ? null : v.missingSince + GRACE, lastUpdate: this.state.boots.find(b => b.id === this.state.bootId)?.updated ?? null }
  }
  private subscription() {
    const s = this.server(); if (s) this.send(s, { type: 'subscription', enabled: Object.keys(this.state.dashboards).length > 0 })
  }
  async attach(socket: Socket) {
    if (socket.attachment.role === 'server') {
      const old = this.server(); if (old && old !== socket) { this.sockets.delete(old); this.failRequests(old); old.close(1000, 'Replaced') }
      this.state.serverLease = this.now() + LEASE
    }
    this.sockets.add(socket)
    if (socket.attachment.role === 'viewer') {
      const d = this.dashboard(socket.attachment.dashboardId), v = d.viewers[socket.attachment.viewerId]
      if (!v) { socket.close(4001, 'Session expired'); this.sockets.delete(socket); return }
      for (const old of this.sockets) if (old !== socket && old.attachment.role === 'viewer' && old.attachment.viewerId === v.id) { this.sockets.delete(old); old.close(1000, 'Replaced') }
      v.lease = this.now() + LEASE
      if (this.online()) v.missingSince = null
      this.send(socket, { type: 'relay.state', ...this.session(d, v) })
    }
    this.subscription(); await this.persist(); this.broadcastState()
  }
  async detach(socket: Socket) {
    if (!this.sockets.delete(socket)) return
    if (socket.attachment.role === 'server') {
      this.state.serverLease = 0; this.failRequests(socket)
      for (const d of Object.values(this.state.dashboards)) for (const v of Object.values(d.viewers)) v.missingSince ??= this.now()
    } else {
      const v = this.state.dashboards[socket.attachment.dashboardId]?.viewers[socket.attachment.viewerId]
      if (v) { v.lease = 0; v.missingSince ??= this.now() }
    }
    await this.persist(); this.broadcastState()
  }
  private broadcastState() {
    for (const s of this.sockets) if (s.attachment.role === 'viewer') {
      const d = this.state.dashboards[s.attachment.dashboardId], v = d?.viewers[s.attachment.viewerId]
      if (v) this.send(s, { type: 'relay.state', ...this.session(d, v) })
    }
  }
  async message(socket: Socket, raw: string) {
    if (!this.sockets.has(socket)) return
    if (raw.length > MAX_FRAME) { socket.close(1009, 'Frame too large'); await this.detach(socket); return }
    let m: Record<string, any>
    try { m = JSON.parse(raw); if (!m || typeof m !== 'object' || Array.isArray(m)) throw new Error() } catch { socket.close(1007, 'Invalid frame'); await this.detach(socket); return }
    await this.sweep()
    if (!this.sockets.has(socket)) return
    if (socket.attachment.role === 'viewer') {
      const d = this.dashboard(socket.attachment.dashboardId), v = d.viewers[socket.attachment.viewerId]
      if (!v) return
      if (m.type !== 'heartbeat') return
      v.lease = this.now() + LEASE
      if (this.online()) v.missingSince = null
      await this.persist(); this.send(socket, { type: 'relay.state', ...this.session(d, v) }); return
    }
    this.state.serverLease = this.now() + LEASE
    if (m.type === 'hello') {
      if (typeof m.bootId !== 'string' || !uuidPattern.test(m.bootId)) throw new Fault(400, 'Invalid boot identity')
      this.state.bootId = m.bootId; socket.attachment.bootId = m.bootId
      for (const d of Object.values(this.state.dashboards)) for (const v of Object.values(d.viewers)) if (v.lease > this.now() && this.viewerOnline(v.id)) v.missingSince = null
      this.subscription(); this.broadcastState()
    } else if (m.type === 'dashboard.create') {
      if (Object.keys(this.state.dashboards).length >= 16) { this.send(socket, { type: 'command.result', requestId: m.requestId, error: 'Dashboard capacity reached' }); return }
      const id = `${this.state.serverUuid}.${random()}`
      this.state.dashboards[id] = { id, created: this.now(), emptySince: this.now(), viewers: {} }
      await this.persist(); this.subscription(); this.send(socket, { type: 'command.result', requestId: m.requestId, dashboardId: id })
    } else if (m.type === 'viewer.trust') {
      let found = false
      for (const d of Object.values(this.state.dashboards)) for (const v of Object.values(d.viewers)) if (!v.approved && v.code === m.code && this.now() < v.created + PENDING) {
        v.approved = true; v.code = ''; found = true
      }
      await this.persist(); this.broadcastState(); this.send(socket, { type: 'command.result', requestId: m.requestId, ...(found ? { trusted: true } : { error: 'Unknown, expired or already used approval code' }) })
    } else if (m.type === 'traffic.snapshot') {
      if (!Object.keys(this.state.dashboards).length || !this.state.bootId || socket.attachment.bootId !== this.state.bootId) return
      if (typeof m.sessionId !== 'string' || typeof m.sequence !== 'number' || typeof m.timestamp !== 'number' || !m.traffic || !Number.isFinite(m.intervalMs) || JSON.stringify(m).length > 8192) return
      let boot = this.state.boots.find(b => b.id === this.state.bootId)
      if (!boot) { boot = { id: this.state.bootId, updated: this.now(), samples: [] }; this.state.boots.push(boot); this.state.boots = this.state.boots.slice(-2) }
      if (!boot.samples.some(s => s.sessionId === m.sessionId && s.sequence === m.sequence)) boot.samples.push(m)
      boot.updated = this.now(); boot.samples = boot.samples.slice(-900)
      while (new TextEncoder().encode(JSON.stringify(this.state.boots)).length > 2 * 1024 * 1024) {
        const first = this.state.boots[0]; first.samples.shift(); if (!first.samples.length && this.state.boots.length > 1) this.state.boots.shift()
      }
      for (const s of this.sockets) if (s.attachment.role === 'viewer' && this.state.dashboards[s.attachment.dashboardId]?.viewers[s.attachment.viewerId]?.approved) this.send(s, m)
    } else if (m.type === 'rpc.chunk') { this.chunk(socket, m); return } // No durable copy of report chunks; heartbeats persist leases.
    else if (m.type !== 'heartbeat') { socket.close(1008, 'Unknown operation'); await this.detach(socket); return }
    await this.persist()
    if (m.type === 'heartbeat') { this.send(socket, { type: 'heartbeat' }); this.broadcastState() }
  }
  private failRequests(socket: Socket) { for (const [id, p] of this.pending) if (p.socket === socket) { clearTimeout(p.timer); p.resolve(json({ error: 'Minecraft server disconnected; operation outcome may be unknown. Do not automatically retry writes.' }, 503)); this.pending.delete(id) } }
  private chunk(socket: Socket, m: Record<string, any>) {
    const p = this.pending.get(m.requestId); if (!p || p.socket !== socket) return
    try {
      if (m.index !== p.index++ || !Number.isInteger(m.status) || m.status < 200 || m.status > 599 || typeof m.data !== 'string' || m.data.length > 65536) throw new Error()
      const bytes = Uint8Array.from(atob(m.data), c => c.charCodeAt(0)); p.bytes += bytes.length
      if (p.bytes > MAX_RESPONSE || p.index > 1100 || p.index > 1 && m.status !== p.status) throw new Error()
      p.status = m.status; p.chunks.push(bytes)
      if (m.final === true) {
        const joined = new Uint8Array(p.bytes); let offset = 0; for (const c of p.chunks) { joined.set(c, offset); offset += c.length }
        const text = new TextDecoder('utf-8', { fatal: true }).decode(joined); JSON.parse(text)
        clearTimeout(p.timer); this.pending.delete(m.requestId); p.resolve(new Response(text, { status: p.status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } }))
      }
    } catch { clearTimeout(p.timer); this.pending.delete(m.requestId); p.resolve(json({ error: 'Invalid or oversized local response' }, 502)) }
  }
  async request(request: Request, id: string, path: string): Promise<Response> {
    const body = request.method === 'POST' ? await boundedBody(request) : ''
    const result = await this.run(async () => {
    await this.sweep()
    const v = await this.viewer(request, id)
    if (!operationAllowed(request.method, path)) throw new Fault(404, 'Operation not allowed')
    if (request.method !== 'GET') {
      this.originCheck(request)
      if (request.headers.get('x-vnp-csrf') !== v.csrf || !request.headers.get('content-type')?.startsWith('application/json')) throw new Fault(403, 'CSRF rejected')
    }
    const socket = this.server()
    // Temporary history remains available when Minecraft is offline, never local reports or settings.
    if (path === '/api/v1/traffic/history') {
      const boot = this.state.boots.find(b => b.id === this.state.bootId), samples = boot?.samples ?? []
      const stream = samples.at(-1)?.sessionId
      return { response: Promise.resolve(json({ bootId: this.state.bootId, sessionId: stream ?? null, samples: samples.filter(s => s.sessionId === stream), serverConnected: this.online(), lastUpdate: boot?.updated ?? null })) }
    }
    if (path === '/api/v1/relay/history') throw new Fault(404, 'Operation not allowed')
    if (!socket || !this.online()) throw new Fault(503, 'Minecraft server disconnected')
    if (this.pending.size >= 8) throw new Fault(429, 'Too many pending requests')
    return { response: new Promise<Response>(resolve => {
      const requestId = crypto.randomUUID()
      const timer = setTimeout(() => { this.pending.delete(requestId); resolve(json({ error: 'Minecraft request timed out; writes are not retried automatically' }, 504)) }, 60_000)
      this.pending.set(requestId, { socket, dashboardId: id, viewerId: v.id, resolve, timer, chunks: [], bytes: 0, index: 0, status: 200 })
      this.send(socket, { type: 'rpc.request', requestId, method: request.method, path, body })
    }) }
    })
    return result.response
  }
}
