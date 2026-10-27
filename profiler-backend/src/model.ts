export const GRACE = 40 * 60_000, LEASE = 45_000, PENDING = 10 * 60_000
export const MAX_RESPONSE = 32 * 1024 * 1024, MAX_FRAME = 96 * 1024
export const uuidPattern = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
export const dashboardPattern = /^([a-f0-9-]{36})\.([a-f0-9]{48})$/
export const random = (bytes = 24) => [...crypto.getRandomValues(new Uint8Array(bytes))].map(n => n.toString(16).padStart(2, '0')).join('')
export async function hash(value: string) { return [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)))].map(n => n.toString(16).padStart(2, '0')).join('') }
export type Viewer = { id: string; credentialHash: string; csrf: string; code: string; approved: boolean; created: number; lease: number; missingSince: number | null }
export type Dashboard = { id: string; created: number; emptySince: number | null; viewers: Record<string, Viewer> }
export type Boot = { id: string; updated: number; samples: Record<string, unknown>[] }
export type State = { serverUuid: string; tokenHash: string; serverLease: number; bootId: string | null; dashboards: Record<string, Dashboard>; boots: Boot[] }
export type Attachment = { role: 'server'; bootId?: string } | { role: 'viewer'; dashboardId: string; viewerId: string }
export interface Socket { send(data: string): void; close(code?: number, reason?: string): void; attachment: Attachment }
export interface Persistence { save(state: State): Promise<void>; alarm(timestamp: number | null): Promise<void> }
export const json = (body: unknown, status = 200, headers: Record<string, string> = {}) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff', 'referrer-policy': 'no-referrer', ...headers } })
export class Fault extends Error { constructor(public status: number, message: string) { super(message) } }
export function validateOrigin(origin: string) {
  const url = new URL(origin)
  if (url.origin !== origin || url.username || url.password || url.protocol !== 'https:' && !(url.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)))
    throw new Error('PUBLIC_ORIGIN must be an HTTPS origin (localhost HTTP is development only)')
  return origin
}
export async function boundedBody(request: Request, limit = 4096): Promise<string> {
  if (!request.body) return ''
  const reader = request.body.getReader(), chunks: Uint8Array[] = []; let bytes = 0
  try {
    while (true) {
      const chunk = await reader.read(); if (chunk.done) break
      bytes += chunk.value.byteLength
      if (bytes > limit) { await reader.cancel(); throw new Fault(413, 'Request too large') }
      chunks.push(chunk.value)
    }
    const body = new Uint8Array(bytes); let offset = 0
    for (const chunk of chunks) { body.set(chunk, offset); offset += chunk.byteLength }
    return new TextDecoder('utf-8', { fatal: true }).decode(body)
  } finally { reader.releaseLock() }
}
export function operationAllowed(method: string, path: string) {
  if (path.length > 2048 || /[#\\]/.test(path)) return false
  const [base, query] = path.split('?')
  if (query && method !== 'GET') return false
  if (method === 'GET') return /^\/api\/v1\/(status|health|traffic\/(current|history)|settings|captures(?:\/[a-f0-9-]{36})?|(?:servers|connections|players)(?:\/[^/]{1,256})?)$/.test(base)
  return method === 'POST' && /^\/api\/v1\/(settings|captures(?:\/[a-f0-9-]{36}\/stop)?)$/.test(base)
}
