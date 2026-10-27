import type { PacketContext, PacketReport, ReportFilters } from './captures'

export type Population = { global: number | null; servers: Record<string, number>; players: Record<string, string>; connections: Record<string, string>; complete: boolean }
export type TimelineSample = { timestamp: number; elapsedMs: number; intervalMs: number; inboundBytes: string; outboundBytes: string; complete: boolean;
  dimensions: { context: PacketContext; inboundBytes: string; outboundBytes: string }[]; population: Population | null }
export type Timeline = { complete: boolean; samples: TimelineSample[] }
export type GraphPoint = { timestamp: number; interval: number; inbound: number | null; outbound: number | null; globalPlayers?: number | null; localPlayers?: number | null }
export const matchesContext = (c: PacketContext, f: ReportFilters) => (!f.server || c.serverId === f.server) && (!f.player || c.playerId === f.player) && (!f.connection || c.connectionId === f.connection)
export function reportPoints(report: PacketReport, filters: ReportFilters): GraphPoint[] {
  const filtered = !!(filters.server || filters.player || filters.connection)
  let population: Population | null = null
  return (report.timeline?.samples ?? []).map(s => {
    if (s.population) population = s.population
    let inbound = s.inboundBytes, outbound = s.outboundBytes
    if (filtered && s.complete) {
      const selected = s.dimensions.filter(d => matchesContext(d.context, filters))
      inbound = String(selected.reduce((n, d) => n + BigInt(d.inboundBytes), 0n)); outbound = String(selected.reduce((n, d) => n + BigInt(d.outboundBytes), 0n))
    }
    const p = population, scope = report.scope
    let server: string | undefined
    if (filters.server) server = filters.server
    else if (filters.connection) server = p?.connections[filters.connection]
    else if (filters.player) server = p?.players[filters.player]
    else if (scope?.type === 'SERVER') server = scope.id ?? undefined
    else if (scope?.type === 'PLAYER') server = p?.players[scope.id ?? '']
    else if (scope?.type === 'CONNECTION') server = p?.connections[scope.id ?? '']
    return { timestamp: s.timestamp, interval: s.intervalMs, inbound: filtered && !s.complete ? null : Number(inbound) * 1000 / s.intervalMs,
      outbound: filtered && !s.complete ? null : Number(outbound) * 1000 / s.intervalMs,
      globalPlayers: p?.global ?? null, localPlayers: server ? p?.servers[server] ?? null : null }
  }).filter(p => p.interval > 0)
}
export function reportRates(report: PacketReport, points: GraphPoint[], direction: 'inbound' | 'outbound' | 'combined') {
  const values = points.map(p => direction === 'combined' ? p.inbound === null || p.outbound === null ? null : p.inbound + p.outbound : p[direction])
  const covered = points.length > 0 && report.durationMs - ((report.timeline?.samples.at(-1)?.elapsedMs) ?? 0) < (report.state === 'RUNNING' ? 2500 : 1)
  const complete = covered && values.every(v => v !== null)
  const valid = values.filter((v): v is number => v !== null)
  const bytes = direction === 'combined' ? report.totalBytes : direction === 'inbound' ? report.inboundBytes : report.outboundBytes
  return { current: covered ? values.at(-1) ?? null : null, mean: report.durationMs > 0 ? Number(bytes) * 1000 / report.durationMs : null,
    min: complete && valid.length ? Math.min(...valid) : null, max: complete && valid.length ? Math.max(...valid) : null }
}
