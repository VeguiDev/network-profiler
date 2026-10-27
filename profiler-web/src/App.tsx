import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Activity, ArrowDownLeft, ArrowUpRight, Cable, Check, ChevronRight, CircleHelp, Clock3, Database, LayoutDashboard, Menu, Moon, Network, Package, Pause, Play, Radio, Server, Settings2, Sun } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { TrafficChart } from '@/components/traffic-chart'
import { cn } from '@/lib/utils'
import { rate, total, uptime, type RateUnit } from '@/lib/format'
import { getJson, type Snapshot, type Status } from '@/lib/types'
import type { LiveTraffic } from '@/lib/live'
import { CaptureControls, PacketReports } from '@/components/packet-reports'
import type { CaptureIndex } from '@/lib/captures'
import { EntityStatistics } from '@/components/entity-statistics'
import { SettingsPage } from '@/components/settings'
import type { Settings } from '@/lib/settings'

const navigation = [
  { name: 'Overview', icon: LayoutDashboard, available: true },
  { name: 'Connections', icon: Cable, available: true }, { name: 'Reports', icon: Package, available: true },
  { name: 'Servers', icon: Server, available: true }, { name: 'Settings', icon: Settings2, available: true },
]
export default function App({ live }: { live: LiveTraffic }) {
  const [path, setPath] = useState(window.location.pathname)
  const navigate = (next: string) => { window.history.pushState(null, '', next); setPath(next); setMenu(false) }
  useEffect(() => { const pop = () => setPath(window.location.pathname); window.addEventListener('popstate', pop); return () => window.removeEventListener('popstate', pop) }, [])
  const reportsPage = path === '/reports' || path.startsWith('/reports/')
  const entitiesPage = /^\/(servers|connections|players)(\/|$)/.test(path)
  const section = path === '/settings' ? 'Settings' : reportsPage ? 'Reports' : entitiesPage ? path.startsWith('/servers') ? 'Servers' : 'Connections' : 'Overview'
  const settings = useQuery({ queryKey: ['settings'], queryFn: ({ signal }) => getJson<Settings>('/api/v1/settings', signal), refetchInterval: 30000, retry: 1 })
  const captureIndex = useQuery({ queryKey: ['captures'], queryFn: ({ signal }) => getJson<CaptureIndex>('/api/v1/captures', signal), refetchInterval: 2000, retry: 1 })
  const state = useSyncExternalStore(live.subscribe, live.getSnapshot)
  const [unit, setUnit] = useState<RateUnit>('bytes')
  const [minutes, setMinutes] = useState(5)
  const [paused, setPaused] = useState(false)
  const frozen = useRef<Snapshot[]>([])
  const [series, setSeries] = useState({ inbound: true, outbound: true })
  const [menu, setMenu] = useState(false)
  const [dark, setDark] = useState(() => localStorage.getItem('network-profiler-theme') !== 'light')
  const status = useQuery({ queryKey: ['status'], queryFn: ({ signal }) => getJson<Status>('/api/v1/status', signal), refetchInterval: 5000, retry: 1 })
  useEffect(() => { live.start(); return live.stop }, [live])
  useEffect(() => { document.documentElement.classList.toggle('dark', dark); localStorage.setItem('network-profiler-theme', dark ? 'dark' : 'light') }, [dark])
  const displayed = paused ? frozen.current : state.samples
  const current = displayed.at(-1)
  const traffic = current?.traffic
  const operational = status.data?.state === 'RUNNING'
  const connected = state.connection === 'Connected'
  const pause = () => { if (!paused) frozen.current = state.samples; setPaused(!paused) }
  const metrics = [
    { label: 'Current inbound', value: rate(traffic?.inbound.bytesPerSecond, unit), sub: `Peak ${rate(traffic?.inbound.peakBytesPerSecond, unit)}`, icon: ArrowDownLeft, color: 'text-inbound', tag: 'IN' },
    { label: 'Current outbound', value: rate(traffic?.outbound.bytesPerSecond, unit), sub: `Peak ${rate(traffic?.outbound.peakBytesPerSecond, unit)}`, icon: ArrowUpRight, color: 'text-outbound', tag: 'OUT' },
    { label: 'Total received', value: total(traffic?.inbound.totalBytes), sub: 'Since session started', icon: Database, color: 'text-muted-foreground', tag: 'IN' },
    { label: 'Total sent', value: total(traffic?.outbound.totalBytes), sub: 'Successful transport writes', icon: Database, color: 'text-muted-foreground', tag: 'OUT' },
  ]
  return <div className="min-h-screen bg-background text-foreground">
    {menu && <button aria-label="Close navigation overlay" className="fixed inset-0 z-30 bg-black/50 lg:hidden" onClick={() => setMenu(false)} />}
    <aside className={cn('fixed inset-y-0 left-0 z-40 flex w-60 flex-col border-r border-border/60 bg-sidebar px-4 py-6 transition-transform lg:translate-x-0', menu ? 'translate-x-0' : '-translate-x-full')}>
      <a href="/" className="mb-10 flex items-center gap-3 px-2" aria-label="Network Profiler home"><span className="flex size-9 items-center justify-center rounded-xl bg-primary/15 text-primary"><Network size={21} /></span><span className="text-sm font-semibold tracking-tight">Network Profiler<span className="mt-0.5 block text-[10px] font-normal uppercase tracking-[0.18em] text-muted-foreground">Minecraft observability</span></span></a>
      <div className="mb-3 px-3 text-[10px] font-medium uppercase tracking-[0.16em] text-muted-foreground">Workspace</div>
      <nav aria-label="Main navigation" className="space-y-1">{navigation.map(item => { const selected = item.name === section; return <button key={item.name} disabled={!item.available} aria-current={selected ? 'page' : undefined} title={item.available ? undefined : 'Not available in this release'} onClick={() => navigate(item.name === 'Overview' ? '/' : `/${item.name.toLowerCase()}`)} className={cn('flex h-10 w-full items-center gap-3 rounded-lg px-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring', selected ? 'bg-primary/10 font-medium text-primary' : 'text-muted-foreground disabled:cursor-not-allowed disabled:opacity-60')}><item.icon size={17} />{item.name}{!item.available && <span className="ml-auto text-[9px] uppercase tracking-wider">Soon</span>}</button> })}</nav>
      <div className="mt-auto px-2"><div className="flex items-center justify-between text-[11px] text-muted-foreground"><span>Open source · v{status.data?.version ?? '0.1.0'}</span><Activity size={13} /></div></div>
    </aside>
    <div className="lg:ml-60">
      <header className="flex h-17 items-center justify-between border-b border-border/60 px-5 sm:px-8 lg:px-10"><div className="flex items-center gap-3 text-xs text-muted-foreground"><Button variant="ghost" size="icon" className="lg:hidden" aria-label="Open navigation" onClick={() => setMenu(true)}><Menu /></Button><span>Workspace</span><ChevronRight size={12} /><span className="text-foreground">{section}</span></div><div className="flex items-center gap-4"><span className="hidden items-center gap-1.5 text-xs text-muted-foreground sm:flex"><Server size={13} />{status.data?.platform ?? 'Velocity'}</span><Button variant="ghost" size="icon" aria-label={dark ? 'Switch to light theme' : 'Switch to dark theme'} onClick={() => setDark(!dark)}>{dark ? <Sun /> : <Moon />}</Button></div></header>
      <main className="mx-auto max-w-[1600px] space-y-7 px-5 py-8 sm:px-8 lg:px-10 lg:py-10">
        {path === '/settings' ? <SettingsPage /> : reportsPage ? <PacketReports id={path.split('/')[2]} index={captureIndex.data} navigate={navigate} /> : entitiesPage ? <EntityStatistics path={path} index={captureIndex.data} navigate={navigate} /> : <>
        <div className="flex flex-wrap items-start justify-between gap-4"><div><div className="mb-2 flex items-center gap-2 text-[10px] font-semibold uppercase tracking-[0.18em] text-primary"><Radio size={13} />Live telemetry</div><h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Network Overview</h1><p className="mt-2 text-sm text-muted-foreground">A clear view of your proxy’s network traffic.</p></div><div role="status" aria-live="polite" className="flex items-center gap-2 rounded-full bg-muted px-3 py-2 text-xs"><span className={cn('size-1.5 rounded-full', connected ? 'bg-primary' : 'bg-amber-400')} />{state.connection}{paused && <span className="text-muted-foreground">· Paused</span>}</div></div>
        {(!operational && status.data || status.isError || !connected || state.historyError) && <div role="status" className="rounded-lg bg-amber-500/8 px-4 py-3 text-sm text-amber-700 dark:text-amber-300">{status.isError ? 'Service unavailable. Displayed data may be stale.' : status.data && !operational ? `${status.data.state}: ${status.data.detail}` : !connected ? 'Live stream unavailable. Reconnecting automatically; displayed values may be stale.' : 'History recovery failed; retrying while live collection continues.'}</div>}
        <CaptureControls index={captureIndex.data} navigate={navigate} />
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">{metrics.map(metric => <Card key={metric.label} className="p-5"><div className="mb-5 flex items-center justify-between"><span className="text-xs font-medium text-muted-foreground">{metric.label}</span><metric.icon size={17} className={metric.color} /></div><div className="truncate text-[27px] font-semibold tracking-tight tabular-nums" title={metric.value}>{metric.value}</div><div className="mt-3 flex items-center justify-between gap-2 text-[11px] text-muted-foreground"><span>{metric.sub}</span><span className={cn('rounded px-1.5 py-0.5 text-[9px] font-medium', metric.tag === 'IN' ? 'bg-inbound/10 text-inbound' : 'bg-outbound/10 text-outbound')}>{metric.tag}</span></div></Card>)}</div>
        <Card className="overflow-hidden"><div className="flex flex-wrap items-center justify-between gap-4 px-5 pb-4 pt-6 sm:px-6"><div><h2 className="text-sm font-semibold">Network throughput</h2><p className="mt-1 text-xs text-muted-foreground">Inbound and outbound · 1 second sampling</p></div><div className="flex flex-wrap items-center gap-2"><div aria-label="Rate units" className="flex rounded-lg bg-muted p-0.5">{(['bytes', 'bits'] as const).map(u => <Button key={u} size="sm" variant="ghost" aria-pressed={unit === u} className={unit === u ? 'bg-card text-foreground shadow-xs' : ''} onClick={() => setUnit(u)}>{u === 'bytes' ? 'Bytes/s' : 'Bits/s'}</Button>)}</div><div aria-label="Time window" className="flex rounded-lg bg-muted p-0.5">{[1, 5, 15].map(m => <Button key={m} size="sm" variant="ghost" aria-pressed={minutes === m} className={minutes === m ? 'bg-card text-foreground shadow-xs' : ''} onClick={() => setMinutes(m)}>{m}m</Button>)}</div><Button variant="outline" size="icon" aria-label={paused ? 'Resume visualization' : 'Pause visualization'} aria-pressed={paused} onClick={pause}>{paused ? <Play /> : <Pause />}</Button></div></div>
          <div className="mb-2 flex items-center gap-5 px-6">{(['inbound', 'outbound'] as const).map(s => <button key={s} aria-pressed={series[s]} className={cn('flex items-center gap-2 rounded py-1 text-xs capitalize outline-none focus-visible:ring-2 focus-visible:ring-ring', !series[s] && 'opacity-40')} onClick={() => setSeries({ ...series, [s]: !series[s] })}><span className={cn('size-2 rounded-full', s === 'inbound' ? 'bg-inbound' : 'bg-outbound')} />{s}{series[s] && <Check size={11} className="text-muted-foreground" />}</button>)}</div>
          <p className="px-6 pb-2 text-xs text-muted-foreground">Proxy players (purple, right axis): {current?.players ?? '—'}</p><div className="px-2 pb-3 sm:px-4"><TrafficChart samples={displayed} minutes={minutes} unit={unit} inbound={series.inbound} outbound={series.outbound} bandwidth={settings.data?.bandwidth} /></div>
          <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border/60 px-6 py-3 text-[11px] text-muted-foreground"><span className="flex items-center gap-1.5"><Clock3 size={12} />{paused ? 'Visualization paused · collection continues' : `Last sample ${current ? new Date(current.timestamp).toLocaleTimeString() : 'pending'}`}</span><span>{displayed.filter(s => s.sequence > 0).length} samples retained</span></div>
        </Card>
        <div className="grid gap-4 md:grid-cols-3"><Card className="flex items-center gap-4 px-5 py-4"><span className="rounded-lg bg-muted p-2.5 text-muted-foreground"><Activity size={17} /></span><div><p className="text-[11px] text-muted-foreground">Combined transferred</p><p className="mt-1 text-sm font-medium tabular-nums">{total(traffic?.combinedTotalBytes)}</p></div></Card><Card className="flex items-center gap-4 px-5 py-4"><span className="rounded-lg bg-muted p-2.5 text-muted-foreground"><Clock3 size={17} /></span><div><p className="text-[11px] text-muted-foreground">Session uptime</p><p className="mt-1 text-sm font-medium tabular-nums">{current ? uptime(current.uptimeMs) : '—'}</p></div></Card><Card className="flex items-center gap-4 px-5 py-4"><span className="rounded-lg bg-muted p-2.5 text-muted-foreground"><Server size={17} /></span><div className="min-w-0"><p className="text-[11px] text-muted-foreground">Platform</p><p className="mt-1 truncate text-sm font-medium">{status.data ? `${status.data.platform} ${status.data.platformVersion}` : 'Waiting for service'}</p></div></Card></div>
        <footer className="flex items-start gap-2 text-[11px] leading-relaxed text-muted-foreground"><CircleHelp size={13} className="mt-0.5 shrink-0" /><p>Client ↔ proxy transport bytes. Outbound counts successful writes, not client acknowledgements. TCP/IP overhead and backend connections are excluded.</p></footer>
        </>}
      </main>
    </div>
  </div>
}
