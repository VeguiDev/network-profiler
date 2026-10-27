import { useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { filterReport, type PacketReport, type ReportFilters } from '@/lib/captures'
import { reportPoints, reportRates } from '@/lib/report-timeline'
import { rate, type RateUnit } from '@/lib/format'
import { getJson } from '@/lib/types'
import type { Settings } from '@/lib/settings'
import { TrafficChart } from './traffic-chart'
import { Card } from './ui/card'
import { Button } from './ui/button'

export function ReportThroughput({ source, filtered, filters }: { source: PacketReport; filtered: PacketReport; filters: ReportFilters }) {
  const settings = useQuery({ queryKey: ['settings'], queryFn: ({ signal }) => getJson<Settings>('/api/v1/settings', signal), refetchInterval: 30000, retry: 1 })
  const [unit, setUnit] = useState<RateUnit>('bytes'), [minutes, setMinutes] = useState(5), [paused, setPaused] = useState(false)
  const [series, setSeries] = useState({ inbound: true, outbound: true, players: true })
  const frozen = useRef<{ source: PacketReport; filtered: PacketReport } | null>(null)
  const shownSource = paused && frozen.current ? frozen.current.source : source
  const shown = useMemo(() => paused ? filterReport(shownSource, filters) : filtered, [paused, shownSource, filters, filtered])
  const points = useMemo(() => reportPoints(shownSource, filters), [shownSource, filters])
  // Filters remain active while paused; only the source time is frozen.
  const pause = () => { if (!paused) frozen.current = { source, filtered }; setPaused(!paused) }
  if (!source.timeline) return <Card className="p-5 text-sm text-muted-foreground">This legacy report has no saved throughput or player history. Start a new capture to collect it.</Card>
  return <Card className="overflow-hidden"><div className="flex flex-wrap items-center justify-between gap-3 p-5"><div><h2 className="text-sm font-semibold">Protocol throughput & players</h2><p className="mt-1 text-xs text-muted-foreground">1 second intervals · follows attribution filters · not transport bytes</p></div><div className="flex flex-wrap gap-1">{(['bytes', 'bits'] as const).map(u => <Button key={u} size="sm" variant={u === unit ? 'secondary' : 'ghost'} onClick={() => setUnit(u)}>{u === 'bytes' ? 'Bytes/s' : 'Bits/s'}</Button>)}{[1, 5, 15].map(m => <Button key={m} size="sm" variant={m === minutes ? 'secondary' : 'ghost'} onClick={() => setMinutes(m)}>{m}m</Button>)}<Button size="sm" variant="outline" onClick={pause}>{paused ? 'Resume chart' : 'Pause chart'}</Button></div></div><div className="flex flex-wrap gap-3 px-5 pb-4">{(['inbound', 'outbound', 'players'] as const).map(s => <Button key={s} size="sm" variant="ghost" aria-pressed={series[s]} onClick={() => setSeries({ ...series, [s]: !series[s] })}>{series[s] ? '●' : '○'} {s}</Button>)}</div><div className="px-2"><TrafficChart points={points} minutes={minutes} unit={unit} inbound={series.inbound} outbound={series.outbound} players={series.players} bandwidth={settings.data?.bandwidth} /></div><div className="grid gap-4 border-t border-border/60 p-5 md:grid-cols-3">{(['inbound', 'outbound', 'combined'] as const).map(d => { const stats = reportRates(shown, points, d); return <div key={d}><h3 className="mb-3 text-xs font-medium capitalize">{d} · full selected capture</h3><dl className="grid grid-cols-2 gap-2 text-xs">{Object.entries(stats).map(([label, value]) => <div key={label}><dt className="capitalize text-muted-foreground">{label === 'current' && source.state !== 'RUNNING' ? 'Last interval' : label}</dt><dd className="mt-1 tabular-nums">{rate(value, unit)}</dd></div>)}</dl></div> })}</div>{!source.timeline.complete && <p role="status" className="px-5 pb-4 text-xs text-amber-600 dark:text-amber-300">Some attribution/player history is unavailable or a history limit was reached. Gaps are unknown, not zero. Global byte counters continue.</p>}<p className="px-5 pb-4 text-[11px] text-muted-foreground">Mean = selected bytes / actual capture duration. Min/max include measured idle intervals. Player counts are snapshots, not packet counts; current-server counts are unavailable while disconnected or unassigned. Pausing never stops collection.</p></Card>
}
