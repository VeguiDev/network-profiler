import { memo, useMemo } from 'react'
import { CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { rate, type RateUnit } from '@/lib/format'
import type { Snapshot } from '@/lib/types'
import type { GraphPoint } from '@/lib/report-timeline'
import { bandwidthBytes, type Bandwidth } from '@/lib/settings'

export const TrafficChart = memo(function TrafficChart({ samples = [], points, bandwidth, minutes, unit, inbound, outbound, players = true }: {
  samples?: Snapshot[]; points?: GraphPoint[]; bandwidth?: Bandwidth; minutes: number; unit: RateUnit; inbound: boolean; outbound: boolean; players?: boolean
}) {
  const data = useMemo(() => {
    const source = points ?? samples.filter(s => s.sequence > 0).map(s => ({ timestamp: s.timestamp, inbound: s.traffic.inbound.bytesPerSecond, outbound: s.traffic.outbound.bytesPerSecond, interval: s.intervalMs, globalPlayers: s.players }))
    const end = source.at(-1)?.timestamp ?? Date.now()
    const visible = source.filter(s => s.timestamp > end - minutes * 60_000)
    const measured: GraphPoint[] = []
    for (const point of visible) {
      const previous = measured.at(-1)
      // A missing relay interval is unknown, not a measured zero or a continuous line.
      // A genuinely long measured interval already covers its own elapsed time.
      if (previous && point.timestamp - previous.timestamp > Math.max(5000, point.interval * 2))
        measured.push({ timestamp: previous.timestamp + 1, interval: 0, inbound: null, outbound: null, globalPlayers: null, localPlayers: null })
      measured.push(point)
    }
    return measured
  }, [samples, points, minutes])
  const inLimit = bandwidthBytes(bandwidth?.inboundMbps), outLimit = bandwidthBytes(bandwidth?.outboundMbps)
  const last = data.at(-1), exceeded = inLimit !== null && (last?.inbound ?? 0) > inLimit || outLimit !== null && (last?.outbound ?? 0) > outLimit
  if (!data.length) return <div className="flex h-[330px] items-center justify-center text-sm text-muted-foreground">Waiting for measured traffic samples…</div>
  return <div><p className="px-4 text-[11px] text-muted-foreground">Players: right axis · {exceeded ? <span className="text-amber-600 dark:text-amber-300">Bandwidth reference exceeded (visual only)</span> : 'Bandwidth references never limit traffic'}</p><div className="h-[330px] w-full" role="img" aria-label="Inbound and outbound network throughput and player counts over time">
    <ResponsiveContainer width="100%" height="100%" minWidth={0}>
      <LineChart data={data} margin={{ top: 15, right: 12, left: 0, bottom: 8 }} accessibilityLayer>
        <CartesianGrid yAxisId="traffic" vertical={false} stroke="var(--chart-grid)" strokeDasharray="3 5" />
        <XAxis dataKey="timestamp" type="number" scale="time" domain={['dataMin', 'dataMax']} minTickGap={55} axisLine={false} tickLine={false} tickMargin={14}
          tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }} tickFormatter={v => new Date(v).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: minutes === 1 ? '2-digit' : undefined })} />
        <YAxis yAxisId="traffic" width={92} axisLine={false} tickLine={false} tickMargin={10} tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }} tickFormatter={v => rate(v, unit)} domain={[0, 'auto']} />
        <YAxis yAxisId="players" orientation="right" width={48} hide={!players} allowDecimals={false} domain={[0, 'auto']} axisLine={false} tickLine={false} tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }} />
        <Tooltip content={({ active, payload, label }) => active && payload?.length ? <div className="rounded-lg border border-border bg-popover p-3 text-xs shadow-xl">
          <p className="mb-2 text-muted-foreground">{new Date(Number(label)).toLocaleTimeString()}</p>
          {payload.map(p => <p key={String(p.dataKey)} className="py-1 tabular-nums" style={{ color: p.color }}>{p.name}: {String(p.dataKey).endsWith('Players') ? `${p.value} players` : <>{rate(p.value as number, unit, true)} <span className="text-muted-foreground">({Number(p.value).toLocaleString(undefined, { maximumFractionDigits: 6 })} B/s)</span></>}</p>)}
          <p className="mt-1 text-muted-foreground">Measured interval: {Number(payload[0]?.payload.interval).toFixed(2)} ms</p>
        </div> : null} />
        {inLimit !== null && inbound && <ReferenceLine yAxisId="traffic" y={inLimit} ifOverflow="extendDomain" stroke="var(--inbound)" strokeDasharray="6 4" label={{ value: 'IN max', fill: 'var(--muted-foreground)', fontSize: 10 }} />}
        {outLimit !== null && outbound && <ReferenceLine yAxisId="traffic" y={outLimit} ifOverflow="extendDomain" stroke="var(--outbound)" strokeDasharray="6 4" label={{ value: 'OUT max', fill: 'var(--muted-foreground)', fontSize: 10 }} />}
        <Line yAxisId="traffic" name="Inbound" dataKey="inbound" hide={!inbound} stroke="var(--inbound)" strokeWidth={2} dot={false} isAnimationActive={false} connectNulls={false} />
        <Line yAxisId="traffic" name="Outbound" dataKey="outbound" hide={!outbound} stroke="var(--outbound)" strokeWidth={2} dot={false} isAnimationActive={false} connectNulls={false} />
        <Line yAxisId="players" name="Proxy players" dataKey="globalPlayers" hide={!players} stroke="#a78bfa" strokeWidth={1.5} strokeDasharray="4 3" dot={false} isAnimationActive={false} connectNulls={false} />
        <Line yAxisId="players" name="Current server players" dataKey="localPlayers" hide={!players} stroke="#fbbf24" strokeWidth={1.5} dot={false} isAnimationActive={false} connectNulls={false} />
      </LineChart>
    </ResponsiveContainer>
  </div></div>
})
