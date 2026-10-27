import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient } from '@tanstack/react-query'
import { LiveTraffic, mergeSamples } from './live'
import type { Snapshot } from './types'
import { configureRemote, type ViewerSession } from './remote'
export function sample(sequence: number, sessionId = 'session-a'): Snapshot {
  return { sessionId, sequence, timestamp: 1790000000000 + sequence * 1000, intervalMs: 1000, uptimeMs: sequence * 1000,
    traffic: { inbound: { available: true, totalBytes: String(sequence * 1000), bytesPerSecond: sequence * 1000, peakBytesPerSecond: sequence * 1000 },
      outbound: { available: true, totalBytes: '0', bytesPerSecond: 0, peakBytesPerSecond: 0 }, combinedTotalBytes: String(sequence * 1000) } }
}
class Socket {
  static instances: Socket[] = []
  onmessage?: (e: { data: string }) => void
  onclose?: () => void
  onerror?: () => void
  readyState = 1
  send() {}
  constructor() { Socket.instances.push(this) }
  message(s: Snapshot) { this.onmessage?.({ data: JSON.stringify({ type: 'traffic.snapshot', ...s }) }) }
  close() { this.onclose?.() }
}
describe('shared live stream', () => {
  let live: LiveTraffic, query: QueryClient
  beforeEach(() => {
    Socket.instances = []; vi.useFakeTimers(); vi.stubGlobal('WebSocket', Socket)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ sessionId: 'session-a', samples: [sample(1), sample(2)] }) }))
    query = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } }); live = new LiveTraffic(query)
  })
  afterEach(() => { live.stop(); query.clear(); configureRemote(undefined); vi.useRealTimers(); vi.unstubAllGlobals() })
  it('deduplicates, orders, bounds and isolates sessions', () => {
    expect(mergeSamples([sample(2)], [sample(1), sample(2), sample(3, 'other')], 'session-a').map(s => s.sequence)).toEqual([1, 2])
    expect(mergeSamples([], Array.from({ length: 1000 }, (_, i) => sample(i)), 'session-a')).toHaveLength(900)
  })
  it('uses one socket and merges recovered history with live messages', async () => {
    live.start(); live.start(); expect(Socket.instances).toHaveLength(1)
    Socket.instances[0].message(sample(3)); await vi.advanceTimersByTimeAsync(0)
    expect(live.getSnapshot().samples.map(s => s.sequence)).toEqual([1, 2, 3])
    expect(live.getSnapshot().connection).toBe('Connected')
  })
  it('reconnects and does not let old-session history overwrite a new session', async () => {
    live.start(); Socket.instances[0].message(sample(3)); await vi.advanceTimersByTimeAsync(0)
    Socket.instances[0].close(); await vi.advanceTimersByTimeAsync(1000)
    expect(Socket.instances).toHaveLength(2)
    Socket.instances[1].message(sample(1, 'session-b')); await vi.advanceTimersByTimeAsync(0)
    expect(live.getSnapshot().samples.map(s => s.sessionId)).toEqual(['session-b'])
    expect(live.getSnapshot().historyError).toBe(true)
  })
  it('stops reconnecting after shutdown', async () => {
    live.start(); live.stop(); await vi.advanceTimersByTimeAsync(60000)
    expect(Socket.instances).toHaveLength(1); expect(live.getSnapshot().connection).toBe('Disconnected')
  })
  it('retains stale samples offline and clears them only when a different Minecraft boot starts', async () => {
    const viewer: ViewerSession = { dashboardId: 'server.dashboard', viewerSessionId: 'browser', state: 'APPROVED', approvalCode: null, csrfToken: 'csrf', serverConnected: true, bootId: 'boot-a', expiresAt: null, lastUpdate: 1000 }
    configureRemote(viewer.dashboardId, viewer); live.setViewer(viewer); live.start()
    Socket.instances[0].message(sample(3)); await vi.advanceTimersByTimeAsync(0)
    Socket.instances[0].onmessage?.({ data: JSON.stringify({ type: 'relay.state', ...viewer, serverConnected: false, expiresAt: Date.now() + 2400000 }) })
    await vi.advanceTimersByTimeAsync(0)
    expect(live.getSnapshot().samples.at(-1)?.sequence).toBe(3)
    expect(live.getSnapshot().viewer?.serverConnected).toBe(false)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ sessionId: null, samples: [] }) }))
    Socket.instances[0].onmessage?.({ data: JSON.stringify({ type: 'relay.state', ...viewer, bootId: 'boot-b' }) })
    await vi.advanceTimersByTimeAsync(0)
    expect(live.getSnapshot().samples).toEqual([])
    Socket.instances[0].message(sample(1, 'new-stream')); await vi.advanceTimersByTimeAsync(0)
    expect(live.getSnapshot().samples.map(s => s.sessionId)).toEqual(['new-stream'])
  })
})
