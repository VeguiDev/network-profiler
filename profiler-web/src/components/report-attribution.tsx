import { useMemo, useState } from 'react'
import { Button } from './ui/button'
import { Card } from './ui/card'
import { filterReport, percentage, serverName, type DirectionTab, type PacketReport, type ReportFilters } from '@/lib/captures'
import { total } from '@/lib/format'

export function ReportAttribution({ report, filters, onChange, tab, navigate }: { report: PacketReport; filters: ReportFilters; onChange: (f: ReportFilters) => void; tab: DirectionTab; navigate: (p: string) => void }) {
  const [group, setGroup] = useState<'server' | 'connection' | 'player'>('server'), [page, setPage] = useState(0)
  const options = useMemo(() => {
    const servers = new Map<string, string>(), players = new Map<string, string>(), connections = new Map<string, string>()
    for (const { context: c } of report.attributions ?? []) {
      servers.set(c.serverId, serverName(c.serverId)); connections.set(c.connectionId, `${c.playerName ?? 'Anonymous'} · ${c.connectionId.slice(0, 8)}`)
      if (c.playerId) players.set(c.playerId, c.playerName ?? c.playerId)
    }
    return { server: servers, player: players, connection: connections }
  }, [report.attributions])
  const selected = useMemo(() => filterReport(report, filters), [report, filters])
  const groups = useMemo(() => {
    const grouped = new Map<string, { id: string; bytes: bigint; name: string }>()
    for (const { context: c, packets } of selected.attributions ?? []) {
      const id = group === 'server' ? c.serverId : group === 'connection' ? c.connectionId : c.playerId ?? ''
      const bytes = packets.filter(p => tab === 'BOTH' || p.direction === tab).reduce((n, r) => n + BigInt(r.totalBytes), 0n)
      const old = grouped.get(id)
      grouped.set(id, { id, bytes: (old?.bytes ?? 0n) + bytes, name: options[group].get(id) ?? 'Unidentified' })
    }
    return [...grouped.values()].sort((a, b) => a.bytes === b.bytes ? a.id.localeCompare(b.id) : a.bytes > b.bytes ? -1 : 1)
  }, [selected.attributions, group, tab, options])
  const denominator = tab === 'BOTH' ? selected.totalBytes : tab === 'INBOUND' ? selected.inboundBytes : selected.outboundBytes
  if (report.schemaVersion < 2) return <p className="text-xs text-muted-foreground">Legacy report · server and connection attribution was not collected.</p>
  return <Card className="space-y-4 p-5"><div><h2 className="text-sm font-medium">Scope: {report.scope?.type ?? 'GLOBAL'} {report.scope?.id && <span className="break-all font-mono text-xs text-muted-foreground">{report.scope.id}</span>}</h2><p className="mt-1 text-xs text-muted-foreground">Server association at observation, not proof of backend origin. Filters affect the metrics and packet table below.</p></div>
    {!report.attributionComplete && <p role="status" className="text-xs text-amber-600 dark:text-amber-300">Attribution is incomplete (limits or unidentified connections). Global totals remain available; filtered totals cover attributed observations only.</p>}
    <div className="flex flex-wrap gap-3">{(['server', 'player', 'connection'] as const).map(key => <label key={key} className="min-w-0 text-xs capitalize">{key}<select aria-label={`Filter report by ${key}`} className="ml-2 max-w-60 rounded-md border border-input bg-background p-2" value={filters[key]} onChange={e => { onChange({ ...filters, [key]: e.target.value }); setPage(0) }}><option value="">All</option>{[...options[key]].map(([id, name]) => <option key={id} value={id}>{name}</option>)}</select></label>)}<Button size="sm" variant="ghost" onClick={() => { onChange({ server: '', player: '', connection: '' }); setPage(0) }}>Clear filters</Button></div>
    <div className="flex gap-2">{(['server', 'connection', 'player'] as const).map(value => <Button size="sm" variant={group === value ? 'secondary' : 'ghost'} key={value} onClick={() => { setGroup(value); setPage(0) }}>By {value}</Button>)}</div>
    <div className="overflow-x-auto"><table className="w-full text-left text-xs"><thead className="text-muted-foreground"><tr>{['Entity', 'Observed bytes', 'Share', ''].map((h, i) => <th className="px-3 py-2 font-medium" key={i}>{h}</th>)}</tr></thead><tbody>{groups.slice(page * 25, page * 25 + 25).map(row => <tr key={row.id} className="border-t border-border/50"><td className="px-3 py-3"><button className="text-primary hover:underline" disabled={!row.id} onClick={() => { onChange({ ...filters, [group]: row.id }); setPage(0) }}>{row.name}</button></td><td className="px-3 py-3 tabular-nums">{total(String(row.bytes))}</td><td className="px-3 py-3 tabular-nums">{percentage(String(row.bytes), denominator)}%</td><td className="px-3 py-3">{row.id && <Button size="sm" variant="ghost" onClick={() => navigate(`/${group === 'server' ? 'servers' : group === 'player' ? 'players' : 'connections'}/${encodeURIComponent(row.id)}`)}>Details</Button>}</td></tr>)}</tbody></table></div>
    {groups.length > 25 && <div className="flex items-center justify-end gap-3 text-xs"><Button size="sm" variant="ghost" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button>{page + 1} / {Math.ceil(groups.length / 25)}<Button size="sm" variant="ghost" disabled={(page + 1) * 25 >= groups.length} onClick={() => setPage(page + 1)}>Next</Button></div>}
  </Card>
}
