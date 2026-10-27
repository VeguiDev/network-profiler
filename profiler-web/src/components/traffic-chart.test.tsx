import type { PropsWithChildren } from 'react'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { TrafficChart } from './traffic-chart'

vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: PropsWithChildren) => <>{children}</>,
  LineChart: ({ children, data }: PropsWithChildren<{ data: unknown }>) => <div data-testid="chart" data-values={JSON.stringify(data)}>{children}</div>,
  YAxis: ({ yAxisId, allowDataOverflow, orientation }: { yAxisId: string; allowDataOverflow?: boolean; orientation?: string }) => <i data-testid={`axis-${yAxisId}`} data-overflow={String(!!allowDataOverflow)} data-orientation={orientation} />,
  ReferenceLine: ({ y, yAxisId, ifOverflow }: { y: number; yAxisId: string; ifOverflow: string }) => <i data-testid="reference" data-value={y} data-axis={yAxisId} data-overflow={ifOverflow} />,
  Line: ({ dataKey, yAxisId }: { dataKey: string; yAxisId: string }) => <i data-testid={`series-${dataKey}`} data-axis={yAxisId} />,
  CartesianGrid: () => null, XAxis: () => null, Tooltip: () => null,
}))
afterEach(cleanup)
it('keeps raw byte geometry, separate player axes and visual capacities without clipping exceeded traffic', () => {
  const points = [{ timestamp: 1000, interval: 1000, inbound: 250001, outbound: 100000, globalPlayers: 12, localPlayers: 4 }]
  render(<TrafficChart points={points} minutes={1} unit="bits" inbound outbound bandwidth={{ inboundMbps: 1, outboundMbps: 2 }} />)
  expect(JSON.parse(screen.getByTestId('chart').getAttribute('data-values')!)).toEqual(points)
  const refs = screen.getAllByTestId('reference')
  expect(refs.map(r => Number(r.getAttribute('data-value')))).toEqual([125000, 250000])
  expect(refs.every(r => r.getAttribute('data-axis') === 'traffic' && r.getAttribute('data-overflow') === 'extendDomain')).toBe(true)
  expect(screen.getByTestId('axis-traffic').getAttribute('data-overflow')).toBe('false')
  expect(screen.getByTestId('axis-players').getAttribute('data-orientation')).toBe('right')
  expect(screen.getByTestId('series-globalPlayers').getAttribute('data-axis')).toBe('players')
  expect(screen.getByTestId('series-localPlayers').getAttribute('data-axis')).toBe('players')
  expect(screen.getByText('Bandwidth reference exceeded (visual only)')).toBeTruthy()
})
