import { test } from 'node:test'
import assert from 'node:assert/strict'
import { Hub } from '../src/hub.js'
import { createApp } from '../src/app.js'
import { Attachment, Fault, GRACE, hash, LEASE, operationAllowed, Socket, State, boundedBody, validateOrigin } from '../src/model.js'

const origin = 'https://mpn.example.test'
class Peer implements Socket {
  frames: any[] = []; closed: number[] = []
  constructor(public attachment: Attachment) {}
  send(data: string) { this.frames.push(JSON.parse(data)) }
  close(code = 1000) { this.closed.push(code) }
  last(type: string) { return this.frames.filter(f => f.type === type).at(-1) }
}
async function setup() {
  let now = 1000000, saved: State | undefined, alarm: number | null = null
  const state: State = { serverUuid: crypto.randomUUID(), tokenHash: await hash('secret'), serverLease: 0, bootId: null, dashboards: {}, boots: [] }
  const store = { save: async (s: State) => { saved = structuredClone(s) }, alarm: async (t: number | null) => { alarm = t } }
  const hub = new Hub(state, store, origin, () => now), server = new Peer({ role: 'server' })
  const message = (peer: Peer, frame: unknown) => hub.run(() => hub.message(peer, JSON.stringify(frame)))
  await hub.attach(server); const boot = crypto.randomUUID(); await message(server, { type: 'hello', bootId: boot })
  await message(server, { type: 'dashboard.create', requestId: 'create' }); const id = server.last('command.result').dashboardId as string
  const req = (cookie = '', method = 'GET', path = 'traffic/history', csrf = '') => new Request(`${origin}/api/v1/dashboard/${id}/proxy/${path}`, { method, headers: { origin, cookie, 'x-vnp-csrf': csrf, 'content-type': 'application/json' }, ...(method === 'POST' ? { body: '{}' } : {}) })
  async function join() {
    const r = await hub.join(req('', 'POST'), id); const session = await r.json() as any
    const cookie = r.headers.get('set-cookie')!.split(';')[0], peer = new Peer({ role: 'viewer', dashboardId: id, viewerId: session.viewerSessionId })
    assert.match(r.headers.get('set-cookie')!, /HttpOnly; SameSite=Strict/); assert.match(r.headers.get('set-cookie')!, /Secure/)
    await hub.attach(peer); return { session, cookie, peer }
  }
  const trust = (code: string) => message(server, { type: 'viewer.trust', code, requestId: 'trust' })
  return { hub, server, boot, id, store, message, req, join, trust, clock: (time: number) => { now = time }, now: () => now, saved: () => saved!, alarm: () => alarm }
}
test('link possession, another browser and another server cannot read or approve a viewer', async () => {
  const a = await setup(), b = await setup(), viewer = await a.join(), other = await a.join()
  await assert.rejects(a.hub.request(a.req(), a.id, '/api/v1/traffic/history'), (e: Fault) => e.status === 401)
  await assert.rejects(a.hub.request(a.req(viewer.cookie), a.id, '/api/v1/traffic/history'), (e: Fault) => e.status === 403)
  await b.trust(viewer.session.approvalCode); assert.ok(b.server.last('command.result').error)
  await a.trust(viewer.session.approvalCode); assert.equal(a.server.last('command.result').trusted, true)
  assert.equal((await a.hub.request(a.req(viewer.cookie), a.id, '/api/v1/traffic/history')).status, 200)
  await assert.rejects(a.hub.request(a.req(other.cookie), a.id, '/api/v1/traffic/history'), (e: Fault) => e.status === 403)
  await a.trust(viewer.session.approvalCode); assert.ok(a.server.last('command.result').error)
  assert.equal(a.saved().tokenHash, await hash('secret')); assert.ok(!JSON.stringify(a.saved()).includes(viewer.cookie.split('=')[1]))
})
test('only approved viewers receive metrics; temporary history stays readable offline without invented zeros', async () => {
  const a = await setup(), yes = await a.join(), no = await a.join(); await a.trust(yes.session.approvalCode)
  const frame = { type: 'traffic.snapshot', sessionId: 'stream', sequence: 1, timestamp: a.now(), intervalMs: 1000, traffic: { inbound: { totalBytes: '100' } } }
  await a.message(a.server, frame)
  assert.deepEqual(yes.peer.last('traffic.snapshot'), frame); assert.equal(no.peer.last('traffic.snapshot'), undefined)
  await a.hub.detach(a.server)
  const r = await a.hub.request(a.req(yes.cookie), a.id, '/api/v1/traffic/history'), data = await r.json() as any
  assert.equal(data.serverConnected, false); assert.deepEqual(data.samples, [frame]); assert.equal(data.bootId, a.boot)
  await assert.rejects(a.hub.request(a.req(yes.cookie), a.id, '/api/v1/settings'), (e: Fault) => e.status === 503)
  assert.equal(yes.peer.last('relay.state').serverConnected, false)
})
test('browser grace expires at exactly forty minutes; another active viewer cannot renew it', async () => {
  const a = await setup(), first = await a.join(), second = await a.join(); await a.trust(first.session.approvalCode); await a.trust(second.session.approvalCode)
  await a.hub.detach(first.peer); const since = a.now()
  for (let t = since + 30000; t < since + GRACE; t += 30000) { a.clock(t); await a.message(a.server, { type: 'heartbeat' }); await a.message(second.peer, { type: 'heartbeat' }) }
  a.clock(since + GRACE - 1); await a.hub.sweep(); assert.ok(a.hub.state.dashboards[a.id].viewers[first.session.viewerSessionId])
  a.clock(since + GRACE); await a.hub.sweep(); assert.equal(a.hub.state.dashboards[a.id].viewers[first.session.viewerSessionId], undefined)
  assert.ok(a.hub.state.dashboards[a.id].viewers[second.session.viewerSessionId]); assert.ok(a.alarm())
})
test('both peers returning cancels grace; reconnect keeps boot samples while actual restart isolates a new boot', async () => {
  const a = await setup(), viewer = await a.join(); await a.trust(viewer.session.approvalCode)
  const frame = { type: 'traffic.snapshot', sessionId: 's1', sequence: 1, timestamp: a.now(), intervalMs: 1000, traffic: {} }
  await a.message(a.server, frame); await a.hub.detach(a.server); await a.hub.detach(viewer.peer)
  a.clock(a.now() + GRACE - 1000); const server = new Peer({ role: 'server' }); await a.hub.attach(server); await a.message(server, { type: 'hello', bootId: a.boot });
  assert.notEqual(a.hub.session(a.hub.dashboard(a.id), a.hub.dashboard(a.id).viewers[viewer.session.viewerSessionId]).expiresAt, null)
  await a.hub.attach(viewer.peer); assert.equal(viewer.peer.last('relay.state').expiresAt, null)
  let data = await (await a.hub.request(a.req(viewer.cookie), a.id, '/api/v1/traffic/history')).json() as any; assert.equal(data.samples.length, 1)
  const newBoot = crypto.randomUUID(); await a.message(server, { type: 'hello', bootId: newBoot })
  data = await (await a.hub.request(a.req(viewer.cookie), a.id, '/api/v1/traffic/history')).json() as any; assert.equal(data.samples.length, 0); assert.equal(data.bootId, newBoot)
})
test('durable restart uses persisted leases, retains history, then deletes final expired dashboard and samples', async () => {
  const a = await setup(), viewer = await a.join(); await a.trust(viewer.session.approvalCode)
  await a.message(a.server, { type: 'traffic.snapshot', sessionId: 's', sequence: 1, timestamp: a.now(), intervalMs: 1000, traffic: {} })
  const restored = new Hub(a.saved(), a.store, origin, a.now)
  a.clock(a.now() + LEASE); await restored.sweep(); assert.equal(restored.online(), false); assert.equal(restored.state.boots.length, 1)
  a.clock(a.now() + GRACE); await restored.sweep(); assert.deepEqual(restored.state.dashboards, {}); assert.deepEqual(restored.state.boots, [])
})
test('pending approval is one-use and expires after ten minutes even if its browser heartbeats', async () => {
  const a = await setup(), v = await a.join(), start = a.now()
  for (let t = start + 30000; t <= start + 600000; t += 30000) { a.clock(t); await a.message(a.server, { type: 'heartbeat' }); await a.message(v.peer, { type: 'heartbeat' }) }
  assert.equal(a.hub.state.dashboards[a.id], undefined); await a.trust(v.session.approvalCode); assert.ok(a.server.last('command.result').error)
})
test('authenticated writes require exact Origin and CSRF and are never retried after disconnection', async () => {
  const a = await setup(), v = await a.join(); await a.trust(v.session.approvalCode)
  await assert.rejects(a.hub.request(a.req(v.cookie, 'POST', 'settings'), a.id, '/api/v1/settings'), (e: Fault) => e.status === 403)
  const foreign = a.req(v.cookie, 'POST', 'settings', v.session.csrfToken); foreign.headers.set('origin', 'https://evil.example')
  await assert.rejects(a.hub.request(foreign, a.id, '/api/v1/settings'), (e: Fault) => e.status === 403)
  const pending = a.hub.request(a.req(v.cookie, 'POST', 'settings', v.session.csrfToken), a.id, '/api/v1/settings')
  while (!a.server.last('rpc.request')) await new Promise(resolve => setImmediate(resolve))
  await a.hub.detach(a.server); assert.equal((await pending).status, 503)
  await a.hub.attach(a.server); assert.equal(a.server.frames.filter(f => f.type === 'rpc.request').length, 1)
})
test('large report chunks reconcile in order and malformed chunks fail boundedly', async () => {
  const a = await setup(), v = await a.join(); await a.trust(v.session.approvalCode)
  async function request() {
    const old = a.server.frames.length, result = a.hub.request(a.req(v.cookie), a.id, '/api/v1/captures')
    while (!a.server.frames.slice(old).some(f => f.type === 'rpc.request')) await new Promise(resolve => setImmediate(resolve))
    return { result: () => result, id: a.server.last('rpc.request').requestId }
  }
  const pending = await request(), bytes = new TextEncoder().encode(JSON.stringify({ report: 'x'.repeat(100000) }))
  for (let i = 0; i * 32768 < bytes.length; i++) await a.message(a.server, { type: 'rpc.chunk', requestId: pending.id, index: i, status: 200, data: Buffer.from(bytes.slice(i * 32768, (i + 1) * 32768)).toString('base64'), final: (i + 1) * 32768 >= bytes.length })
  assert.equal((await (await pending.result()).json() as any).report.length, 100000)
  const invalid = await request(); await a.message(a.server, { type: 'rpc.chunk', requestId: invalid.id, index: 2, status: 200, data: 'e30=', final: true }); assert.equal((await invalid.result()).status, 502)
})
test('Hono router never exposes internal registration or unapproved proxy data', async () => {
  const a = await setup()
  const app = createApp({ origin, register: async () => ({ serverUuid: a.hub.state.serverUuid, serverToken: 'test' }), hub: async id => id === a.hub.state.serverUuid ? a.hub : null,
    upgrade: () => new Response(null, { status: 426 }), assets: async () => new Response('shell') })
  const prefix = `/api/v1/dashboard/${a.id}`
  assert.equal((await app.request(prefix + '/proxy/traffic/history')).status, 401)
  const join = await app.request(prefix + '/viewer', { method: 'POST', headers: { Origin: origin } }); assert.equal(join.status, 201)
  const cookie = join.headers.get('set-cookie')!.split(';')[0], session = await join.json() as any
  assert.equal((await app.request(prefix + '/proxy/traffic/history', { headers: { cookie } })).status, 403)
  await a.trust(session.approvalCode)
  assert.equal((await app.request(prefix + '/proxy/traffic/history', { headers: { cookie } })).status, 200)
  assert.equal((await app.request('/internal/register', { method: 'POST' })).status, 404)
  assert.equal((await app.request(`/api/v1/events/${a.hub.state.serverUuid}`, { headers: { Authorization: 'Bearer wrong' } })).status, 401)
  for (const path of ['/api/v1/exec', '/api/v1/../../etc/passwd', '/api/v1/settings#fragment']) assert.equal(operationAllowed('POST', path), false)
})
test('an in-flight report cannot outlive the browser that authorized it', async () => {
  const a = await setup(), v = await a.join(); await a.trust(v.session.approvalCode)
  const result = a.hub.request(a.req(v.cookie), a.id, '/api/v1/captures')
  while (!a.server.last('rpc.request')) await new Promise(resolve => setImmediate(resolve))
  const id = a.server.last('rpc.request').requestId
  await a.hub.detach(v.peer)
  for (let t = a.now() + 30000, end = a.now() + GRACE; t <= end; t += 30000) { a.clock(t); await a.message(a.server, { type: 'heartbeat' }) }
  assert.equal((await result).status, 410)
  await a.message(a.server, { type: 'rpc.chunk', requestId: id, index: 0, status: 200, data: 'e30=', final: true })
  assert.equal(v.peer.last('rpc.chunk'), undefined)
})
test('streamed request bodies are bounded independently of Content-Length; remote origins require TLS', async () => {
  await assert.rejects(boundedBody(new Request(origin, { method: 'POST', body: 'x'.repeat(4097) })), (e: Fault) => e.status === 413)
  assert.equal(await boundedBody(new Request(origin, { method: 'POST', body: '{}' })), '{}')
  assert.doesNotThrow(() => validateOrigin('https://mpn.example.test'))
  assert.doesNotThrow(() => validateOrigin('http://127.0.0.1:3000'))
  for (const invalid of ['http://example.test', 'https://example.test/path', 'https://user@example.test', 'file://localhost']) assert.throws(() => validateOrigin(invalid))
})
