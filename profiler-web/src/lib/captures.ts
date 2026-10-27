import { getJson } from './types'
import type { Timeline } from './report-timeline'
import { apiFetch, currentViewer } from './remote'

export type PacketRow = { name: string; protocolState: string; direction: 'INBOUND' | 'OUTBOUND'; totalBytes: string; packetCount: string; cancelledPackets: string; modifiedPackets: string }
export type CaptureScope = { type: 'GLOBAL' | 'SERVER' | 'PLAYER' | 'CONNECTION'; id?: string | null }
export type PacketContext = { connectionId: string; playerId: string | null; playerName: string | null; serverId: string }
export type Attribution = { context: PacketContext; packets: PacketRow[] }
export type PacketReport = { schemaVersion: number; id: string; state: 'RUNNING' | 'FINALIZING' | 'COMPLETED' | 'INTERRUPTED' | 'SAVE_FAILED'; startedAt: number; endedAt: number | null; durationMs: number; detail: string; inboundBytes: string; outboundBytes: string; totalBytes: string; packetCount: string; cancelledPackets: string; modifiedPackets: string; observationErrors: string; packets: PacketRow[]; scope?: CaptureScope; attributionComplete?: boolean; attributions?: Attribution[]; timeline?: Timeline | null }
export type CaptureIndex = { available: boolean; detail: string; csrfToken: string; maxDurationSeconds: number; reports: PacketReport[] }
export type DirectionTab = 'BOTH' | 'OUTBOUND' | 'INBOUND'
export const serverName = (id: string) => id === '~unassigned' ? 'Unassigned / pre-login' : id === '~transition' ? 'Transition' : id
export type ReportFilters = { server: string; player: string; connection: string }
export function filterReport(report: PacketReport, filters: ReportFilters): PacketReport {
  if (!filters.server && !filters.player && !filters.connection) return report
  const attributions = (report.attributions ?? []).filter(a => (!filters.server || a.context.serverId === filters.server)
    && (!filters.player || a.context.playerId === filters.player) && (!filters.connection || a.context.connectionId === filters.connection))
  const merged = new Map<string, PacketRow>()
  for (const a of attributions) for (const row of a.packets) {
    const key = `${row.direction}:${row.protocolState}:${row.name}`, old = merged.get(key)
    merged.set(key, old ? { ...row, totalBytes: String(BigInt(old.totalBytes) + BigInt(row.totalBytes)), packetCount: String(BigInt(old.packetCount) + BigInt(row.packetCount)),
      cancelledPackets: String(BigInt(old.cancelledPackets) + BigInt(row.cancelledPackets)), modifiedPackets: String(BigInt(old.modifiedPackets) + BigInt(row.modifiedPackets)) } : row)
  }
  const packets = [...merged.values()]
  const sum = (key: 'totalBytes' | 'packetCount' | 'cancelledPackets' | 'modifiedPackets', dir?: string) => String(packets.filter(r => !dir || r.direction === dir).reduce((n, r) => n + BigInt(r[key]), 0n))
  return { ...report, attributions, packets, inboundBytes: sum('totalBytes', 'INBOUND'), outboundBytes: sum('totalBytes', 'OUTBOUND'), totalBytes: sum('totalBytes'),
    packetCount: sum('packetCount'), cancelledPackets: sum('cancelledPackets'), modifiedPackets: sum('modifiedPackets') }
}
export function percentage(bytes: string, denominator: string): number {
  const total = BigInt(denominator)
  return total === 0n ? 0 : Number(BigInt(bytes) * 10000n / total) / 100
}
export function packetRows(report: PacketReport, direction: DirectionTab, search = '') {
  return report.packets.filter(row => (direction === 'BOTH' || row.direction === direction)
    && `${row.name} ${row.protocolState}`.toLowerCase().includes(search.toLowerCase()))
    .sort((a, b) => BigInt(a.totalBytes) === BigInt(b.totalBytes) ? a.name.localeCompare(b.name) : BigInt(a.totalBytes) > BigInt(b.totalBytes) ? -1 : 1)
}
export async function mutateCapture(path: string, body: Record<string, unknown> = {}): Promise<PacketReport> {
  // Fetch a current token so a proxy restart cannot leave an old page with an unusable token.
  const token = currentViewer()?.csrfToken ?? (await getJson<CaptureIndex>('/api/v1/captures')).csrfToken
  const response = await apiFetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-VNP-CSRF': token }, body: JSON.stringify(body) })
  if (!response.ok) throw new Error(`Capture request failed (${response.status}): ${(await response.text()).slice(0, 300)}`)
  return response.json() as Promise<PacketReport>
}
