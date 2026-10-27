export type Direction = { available: boolean; totalBytes: string | null; bytesPerSecond: number | null; peakBytesPerSecond: number | null }
export type Snapshot = {
  sessionId: string; sequence: number; timestamp: number; intervalMs: number; uptimeMs: number
  traffic: { inbound: Direction; outbound: Direction; combinedTotalBytes: string | null }
  players?: number | null
}
export type TrafficHistory = { sessionId: string; samples: Snapshot[] }
export type Status = {
  state: 'STARTING' | 'RUNNING' | 'DISABLED' | 'ERROR' | 'STOPPED'; detail: string
  platform: string; platformVersion: string; version: string; sessionId: string; uptimeMs: number; capabilities: string[]
}
export async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(path, { signal, cache: 'no-store' })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return response.json() as Promise<T>
}
export function isSnapshot(value: unknown): value is Snapshot {
  if (!value || typeof value !== 'object') return false
  const s = value as Snapshot
  const validDirection = (d: Direction) => !!d && typeof d.available === 'boolean'
    && (d.totalBytes === null || typeof d.totalBytes === 'string' && /^\d+$/.test(d.totalBytes))
    && (d.bytesPerSecond === null || typeof d.bytesPerSecond === 'number' && Number.isFinite(d.bytesPerSecond) && d.bytesPerSecond >= 0)
    && (d.peakBytesPerSecond === null || typeof d.peakBytesPerSecond === 'number' && Number.isFinite(d.peakBytesPerSecond) && d.peakBytesPerSecond >= 0)
  return typeof s.sessionId === 'string' && Number.isSafeInteger(s.sequence) && s.sequence >= 0
    && Number.isFinite(s.timestamp) && Number.isFinite(s.intervalMs) && Number.isFinite(s.uptimeMs)
    && !!s.traffic && validDirection(s.traffic.inbound) && validDirection(s.traffic.outbound)
}
