import { useEffect, useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, CircleHelp, FileChartColumn, Play, Radio, Square } from 'lucide-react'
import { Button } from './ui/button'
import { Card } from './ui/card'
import { getJson } from '@/lib/types'
import { total, uptime } from '@/lib/format'
import { filterReport, mutateCapture, packetRows, percentage, type CaptureIndex, type CaptureScope, type DirectionTab, type PacketReport, type ReportFilters } from '@/lib/captures'
import { ReportAttribution } from './report-attribution'
import { ReportThroughput } from './report-throughput'
import { cn } from '@/lib/utils'

export function CaptureControls({ index, navigate, scope = { type: 'GLOBAL' }, label }: { index?: CaptureIndex; navigate: (path: string) => void; scope?: CaptureScope; label?: string }) {
  const query = useQueryClient()
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const active = index?.reports?.find(r => r.state === 'RUNNING' || r.state === 'FINALIZING')
  const start = async () => {
    setBusy(true); setError('')
    try {
      const report = await mutateCapture('/api/v1/captures', scope.type === 'GLOBAL' ? {} : { scope })
      query.setQueryData(['report', report.id], report)
      await query.invalidateQueries({ queryKey: ['captures'] })
      navigate(`/reports/${report.id}`)
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not start capture') }
    finally { setBusy(false) }
  }
  return <Card className="p-5"><div className="flex flex-wrap items-center justify-between gap-4"><div className="flex items-center gap-3"><span className="rounded-lg bg-primary/10 p-2 text-primary"><FileChartColumn size={20} /></span><div><h2 className="text-sm font-medium">Packet analysis {label && <span className="text-muted-foreground">· {label}</span>} {active && <span className="ml-2 text-xs text-primary">● {active.state === 'RUNNING' ? 'Recording' : 'Finalizing'}</span>}</h2><p className="mt-1 text-xs text-muted-foreground">{active ? 'One shared capture is active. Open it or stop it before starting another scope.' : index?.available ? `On-demand detail by packet type · auto-stop after ${uptime((index.maxDurationSeconds ?? 900) * 1000)}` : index?.detail ?? 'Checking packet analysis availability…'}</p></div></div><div className="flex gap-2"><Button variant="ghost" onClick={() => navigate('/reports')}>Reports</Button>{active ? <Button onClick={() => navigate(`/reports/${active.id}`)}>View capture<ArrowRight /></Button> : <Button disabled={!index?.available || busy} onClick={() => void start()}><Play />{busy ? 'Starting…' : 'Start analysis'}</Button>}</div></div>{error && <p role="alert" className="mt-3 text-sm text-red-500">{error}</p>}</Card>
}

export function PacketReports({ id, index, navigate }: { id?: string; index?: CaptureIndex; navigate: (path: string) => void }) {
  const query = useQueryClient()
  const reportQuery = useQuery({ queryKey: ['report', id], queryFn: ({ signal }) => getJson<PacketReport>(`/api/v1/captures/${id}`, signal), enabled: !!id,
    refetchInterval: q => q.state.data?.state === 'RUNNING' || q.state.data?.state === 'FINALIZING' ? 1000 : false, retry: 1 })
  const [filters, setFilters] = useState<ReportFilters>({ server: '', player: '', connection: '' })
  useEffect(() => { setFilters({ server: '', player: '', connection: '' }) }, [id])
  const sourceReport = reportQuery.data
  const report = useMemo(() => sourceReport ? filterReport(sourceReport, filters) : undefined, [sourceReport, filters])
  const [tab, setTab] = useState<DirectionTab>('BOTH'), [search, setSearch] = useState(''), [page, setPage] = useState(0)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  useEffect(() => { setPage(0) }, [tab, search, id, filters])
  const rows = useMemo(() => report ? packetRows(report, tab, search) : [], [report, tab, search])
  const denominator = report ? tab === 'BOTH' ? report.totalBytes : tab === 'INBOUND' ? report.inboundBytes : report.outboundBytes : '0'
  const stop = async () => {
    if (!report) return
    setBusy(true); setError('')
    try {
      const result = await mutateCapture(`/api/v1/captures/${report.id}/stop`)
      query.setQueryData(['report', report.id], result)
      await query.invalidateQueries({ queryKey: ['captures'] })
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not stop capture') }
    finally { setBusy(false) }
  }
  if (!id) return <div className="space-y-6"><div><h1 className="text-3xl font-semibold tracking-tight">Packet reports</h1><p className="mt-2 text-sm text-muted-foreground">Capture a time window. Find out which packet types dominate it.</p></div><CaptureControls index={index} navigate={navigate} /><Card className="overflow-hidden"><div className="border-b border-border/60 px-6 py-4 text-sm font-medium">Saved captures</div>{index?.reports?.length ? index.reports.map(r => <button key={r.id} onClick={() => navigate(`/reports/${r.id}`)} className="flex w-full flex-wrap items-center justify-between gap-3 border-b border-border/40 px-6 py-5 text-left transition-colors last:border-0 hover:bg-muted/50"><div><p className="text-sm font-medium">{r.startedAt ? new Date(r.startedAt).toLocaleString() : 'Finalizing capture'}</p><p className="mt-1 font-mono text-[11px] text-muted-foreground">{r.id}</p></div><div className="flex items-center gap-5 text-xs"><span className="text-muted-foreground">{uptime(r.durationMs)}</span><span>{total(r.totalBytes)}</span><span className={r.state === 'RUNNING' ? 'text-primary' : 'text-muted-foreground'}>{r.state}</span><ArrowRight size={15} /></div></button>) : <p className="px-6 py-12 text-center text-sm text-muted-foreground">No captures yet. Start an analysis here or run /mnp start.</p>}</Card></div>
  return <div className="space-y-6"><Button variant="ghost" onClick={() => navigate('/reports')}><ArrowLeft />All reports</Button>{reportQuery.isError ? <p role="alert" className="text-sm text-red-500">Report unavailable. It may have expired, or the server is offline.</p> : !report ? <p role="status">Loading report…</p> : <><div className="flex flex-wrap items-start justify-between gap-4"><div><div className="mb-2 flex items-center gap-2 text-[10px] font-semibold uppercase tracking-widest text-primary"><Radio size={13} />{report.state === 'RUNNING' ? 'Live capture · updates every second' : report.state}</div><h1 className="text-3xl font-semibold tracking-tight">Packet analysis</h1><p className="mt-2 text-xs text-muted-foreground">{report.startedAt ? new Date(report.startedAt).toLocaleString() : ''} · {uptime(report.durationMs)}</p><p className="mt-1 break-all font-mono text-[10px] text-muted-foreground">{report.id}</p></div>{report.state === 'RUNNING' && <Button disabled={busy} onClick={() => void stop()}><Square />{busy ? 'Finalizing…' : 'Stop & save report'}</Button>}</div>{error && <p role="alert" className="text-sm text-red-500">{error}</p>}{!['RUNNING', 'COMPLETED'].includes(report.state) && <p role="status" className="rounded-lg bg-amber-500/10 p-4 text-sm text-amber-700 dark:text-amber-300">{report.detail}</p>}
    {sourceReport && <ReportAttribution report={sourceReport} filters={filters} onChange={setFilters} tab={tab} navigate={navigate} />}
    {sourceReport && <ReportThroughput key={sourceReport.id} source={sourceReport} filtered={report} filters={filters} />}
    <div className="grid gap-4 sm:grid-cols-3">{[{ label: 'Protocol inbound', value: total(report.inboundBytes), color: 'text-inbound' }, { label: 'Protocol outbound', value: total(report.outboundBytes), color: 'text-outbound' }, { label: 'Observed packets', value: BigInt(report.packetCount).toLocaleString(), color: '' }].map(metric => <Card key={metric.label} className="p-5"><p className="mb-3 text-xs text-muted-foreground">{metric.label}</p><p className={cn('text-2xl font-semibold tabular-nums', metric.color)}>{metric.value}</p></Card>)}</div>
    <Card className="overflow-hidden"><div className="flex flex-wrap items-center justify-between gap-3 p-5"><div role="tablist" aria-label="Packet direction" className="flex rounded-lg bg-muted p-1">{(['BOTH', 'OUTBOUND', 'INBOUND'] as const).map(value => <Button key={value} role="tab" aria-selected={tab === value} variant="ghost" size="sm" className={tab === value ? 'bg-card text-foreground shadow-xs' : ''} onClick={() => setTab(value)}>{value === 'BOTH' ? 'BOTH' : value === 'OUTBOUND' ? 'Outbound' : 'Inbound'}</Button>)}</div><input aria-label="Search packet types" placeholder="Search packet types…" value={search} onChange={e => setSearch(e.target.value)} className="h-9 min-w-0 rounded-lg border border-input bg-background px-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring" /></div><div className="overflow-x-auto"><table className="w-full text-left text-xs"><thead className="border-y border-border/60 bg-muted/30 text-muted-foreground"><tr>{['Packet type', 'Direction', 'Total bytes', 'Share', 'Packets', 'Mean size'].map(label => <th key={label} className="whitespace-nowrap px-5 py-3 font-medium">{label}</th>)}</tr></thead><tbody>{rows.slice(page * 50, page * 50 + 50).map(row => { const share = percentage(row.totalBytes, denominator); const count = BigInt(row.packetCount); return <tr key={`${row.direction}:${row.protocolState}:${row.name}`} className="border-b border-border/40 last:border-0 hover:bg-muted/20"><td className="min-w-52 px-5 py-4"><p className="font-mono text-[11px] font-medium">{row.name}</p><p className="mt-1 text-[10px] text-muted-foreground">{row.protocolState}{row.cancelledPackets !== '0' && ` · ${row.cancelledPackets} cancelled`}</p></td><td className={cn('px-5 py-4', row.direction === 'INBOUND' ? 'text-inbound' : 'text-outbound')}>{row.direction === 'INBOUND' ? 'IN' : 'OUT'}</td><td className="whitespace-nowrap px-5 py-4 tabular-nums" title={`${BigInt(row.totalBytes).toLocaleString()} B`}>{total(row.totalBytes)}</td><td className="min-w-40 px-5 py-4"><div className="mb-1.5 tabular-nums">{share.toFixed(2)}%</div><div className="h-1 rounded-full bg-muted"><div className={cn('h-full rounded-full', row.direction === 'INBOUND' ? 'bg-inbound' : 'bg-outbound')} style={{ width: `${share}%` }} /></div></td><td className="px-5 py-4 tabular-nums">{count.toLocaleString()}</td><td className="whitespace-nowrap px-5 py-4 tabular-nums">{count ? `${(Number(BigInt(row.totalBytes) * 100n / count) / 100).toLocaleString(undefined, { maximumFractionDigits: 2 })} B` : '—'}</td></tr> })}</tbody></table>{!rows.length && <p className="p-10 text-center text-sm text-muted-foreground">{report.state === 'RUNNING' ? 'Waiting for matching Minecraft packets…' : 'No matching packets in this capture.'}</p>}</div><div className="flex items-center justify-between gap-3 border-t border-border/60 px-5 py-3 text-xs text-muted-foreground"><span>{rows.length} types · percentages use all bytes in this tab, including filtered rows</span><div className="flex gap-1"><Button size="sm" variant="ghost" disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</Button><Button size="sm" variant="ghost" disabled={(page + 1) * 50 >= rows.length} onClick={() => setPage(p => p + 1)}>Next</Button></div></div></Card>
    <div className="flex gap-2 text-xs leading-relaxed text-muted-foreground"><CircleHelp size={15} className="mt-0.5 shrink-0" /><div><p>PacketEvents MONITOR observations: packet ID + payload before compression/encryption and possible re-encoding. OUT does not confirm a successful write. These totals are not the transport totals in Overview.</p><p className="mt-2">Cancelled at observation: {report.cancelledPackets} (excluded from bytes/counts) · Pending re-encoding: {report.modifiedPackets} · Observation errors: {report.observationErrors}</p><p className="mt-2">{report.detail}</p></div></div></>}</div>
}
