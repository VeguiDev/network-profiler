import { QueryClient } from '@tanstack/react-query'
import { getJson, isSnapshot, type Snapshot, type TrafficHistory } from './types'

export type Connection = 'Connected' | 'Reconnecting' | 'Disconnected'
export type LiveState = { samples: Snapshot[]; connection: Connection; historyError: boolean }
export function mergeSamples(existing: Snapshot[], incoming: Snapshot[], sessionId: string): Snapshot[] {
  const bySequence = new Map<number, Snapshot>()
  for (const s of [...existing, ...incoming]) if (s.sessionId === sessionId && isSnapshot(s)) bySequence.set(s.sequence, s)
  const sorted = [...bySequence.values()].sort((a, b) => a.sequence - b.sequence)
  const latest = sorted.at(-1)
  return latest ? sorted.filter(s => s.timestamp > latest.timestamp - 900_000).slice(-900) : []
}

/** One owner, one socket; components subscribe to the store, never open sockets. */
export class LiveTraffic {
  private state: LiveState = { samples: [], connection: 'Disconnected', historyError: false }
  private listeners = new Set<() => void>()
  private socket?: WebSocket
  private retry?: ReturnType<typeof setTimeout>
  private stale?: ReturnType<typeof setInterval>
  private running = false
  private generation = 0
  private attempts = 0
  private session?: string
  constructor(private query: QueryClient) {}
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private update(patch: Partial<LiveState>) { this.state = { ...this.state, ...patch }; this.listeners.forEach(fn => fn()) }
  start = () => { if (this.running) return; this.running = true; this.connect() }
  stop = () => {
    this.running = false; this.generation++
    clearTimeout(this.retry); clearInterval(this.stale)
    this.socket?.close(); this.socket = undefined
    void this.query.cancelQueries({ queryKey: ['traffic-history'] })
    this.update({ connection: 'Disconnected' })
  }
  private connect() {
    if (!this.running) return
    const generation = ++this.generation
    this.update({ connection: this.attempts >= 6 ? 'Disconnected' : 'Reconnecting' })
    const url = new URL('/api/v1/live', window.location.href)
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    const ws = this.socket = new WebSocket(url)
    let lastFrame = Date.now(), recoveredSession: string | undefined
    const stale = this.stale = setInterval(() => { if (Date.now() - lastFrame > 6000) ws.close() }, 2000)
    ws.onmessage = event => {
      if (!this.running || generation !== this.generation) return
      let message: unknown
      try { message = JSON.parse(String(event.data)) } catch { ws.close(1008, 'Invalid JSON'); return }
      if (!isSnapshot(message) || (message as { type?: string }).type !== 'traffic.snapshot') { ws.close(1008, 'Invalid snapshot'); return }
      lastFrame = Date.now(); this.attempts = 0
      if (this.session !== message.sessionId) { this.session = message.sessionId; this.update({ samples: [] }) }
      this.update({ connection: 'Connected', samples: mergeSamples(this.state.samples, [message], message.sessionId) })
      if (recoveredSession !== message.sessionId) {
        recoveredSession = message.sessionId
        void this.recover(generation, message.sessionId).catch(() => { recoveredSession = undefined })
      }
    }
    ws.onerror = () => ws.close()
    ws.onclose = () => {
      clearInterval(stale)
      if (!this.running || generation !== this.generation) return
      this.attempts++
      this.update({ connection: this.attempts >= 6 ? 'Disconnected' : 'Reconnecting' })
      const delay = Math.min(30_000, 500 * 2 ** Math.min(this.attempts - 1, 6)) * (0.8 + Math.random() * 0.4)
      this.retry = setTimeout(() => this.connect(), delay)
    }
  }
  private async recover(generation: number, session: string) {
    try {
      await this.query.cancelQueries({ queryKey: ['traffic-history'] })
      const history = await this.query.fetchQuery({ queryKey: ['traffic-history'], staleTime: 0, retry: false,
        queryFn: ({ signal }) => getJson<TrafficHistory>('/api/v1/traffic/history', signal) })
      if (!this.running || generation !== this.generation || session !== this.session) return
      if (history.sessionId !== session || !Array.isArray(history.samples)) throw new Error('Session changed during recovery')
      this.update({ historyError: false, samples: mergeSamples(this.state.samples, history.samples, session) })
    } catch (error) {
      if (generation === this.generation) this.update({ historyError: true })
      throw error
    }
  }
}
