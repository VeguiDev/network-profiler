import { memo, useMemo } from 'react'
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { rate, type RateUnit } from '@/lib/format'
import type { Snapshot } from '@/lib/types'

export const TrafficChart = memo(function TrafficChart({ samples, minutes, unit, inbound, outbound }: {
  samples: Snapshot[]; minutes: number; unit: RateUnit; inbound: boolean; outbound: boolean
}) {
  const data = useMemo(() => {
    const end = samples.at(-1)?.timestamp ?? Date.now()
    return samples.filter(s => s.sequence > 0 && s.timestamp > end - minutes * 60_000)
      .map(s => ({ timestamp: s.timestamp, inbound: s.traffic.inbound.bytesPerSecond, outbound: s.traffic.outbound.bytesPerSecond, interval: s.intervalMs }))
  }, [samples, minutes])
  if (!data.length) return <div className="flex h-[330px] items-center justify-center text-sm text-muted-foreground">Waiting for measured traffic samples…</div>
  return <div className="h-[330px] w-full" role="img" aria-label="Inbound and outbound network throughput over time">
    <ResponsiveContainer width="100%" height="100%" minWidth={0}>
      <LineChart data={data} margin={{ top: 15, right: 12, left: 0, bottom: 8 }} accessibilityLayer>
        <CartesianGrid vertical={false} stroke="var(--chart-grid)" strokeDasharray="3 5" />
        <XAxis dataKey="timestamp" type="number" scale="time" domain={['dataMin', 'dataMax']} minTickGap={55} axisLine={false} tickLine={false} tickMargin={14}
          tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }} tickFormatter={v => new Date(v).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: minutes === 1 ? '2-digit' : undefined })} />
        <YAxis width={92} axisLine={false} tickLine={false} tickMargin={10} tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }} tickFormatter={v => rate(v, unit)} domain={[0, 'auto']} />
        <Tooltip content={({ active, payload, label }) => active && payload?.length ? <div className="rounded-lg border border-border bg-popover p-3 text-xs shadow-xl">
          <p className="mb-2 text-muted-foreground">{new Date(Number(label)).toLocaleTimeString()}</p>
          {payload.map(p => <p key={String(p.dataKey)} className="py-1 tabular-nums" style={{ color: p.color }}>{p.name}: {rate(p.value as number, unit, true)} <span className="text-muted-foreground">({Number(p.value).toLocaleString(undefined, { maximumFractionDigits: 6 })} B/s)</span></p>)}
          <p className="mt-1 text-muted-foreground">Measured interval: {Number(payload[0]?.payload.interval).toFixed(2)} ms</p>
        </div> : null} />
        <Line name="Inbound" dataKey="inbound" hide={!inbound} stroke="var(--inbound)" strokeWidth={2} dot={false} isAnimationActive={false} connectNulls={false} />
        <Line name="Outbound" dataKey="outbound" hide={!outbound} stroke="var(--outbound)" strokeWidth={2} dot={false} isAnimationActive={false} connectNulls={false} />
      </LineChart>
    </ResponsiveContainer>
  </div>
})
