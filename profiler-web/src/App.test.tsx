import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from './App'
import type { LiveState, LiveTraffic } from './lib/live'
import type { Snapshot } from './lib/types'
vi.mock('./components/traffic-chart', () => ({ TrafficChart: () => <div>Chart</div> }))
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
it('pauses only presentation and catches up on resume', async () => {
  const sample = (sequence: number): Snapshot => ({ sessionId: 's', sequence, timestamp: Date.now(), intervalMs: 1000, uptimeMs: 1000,
    traffic: { inbound: { available: true, totalBytes: '1', bytesPerSecond: sequence * 1000, peakBytesPerSecond: 5000 },
      outbound: { available: true, totalBytes: '0', bytesPerSecond: 0, peakBytesPerSecond: 0 }, combinedTotalBytes: '1' } })
  let state: LiveState = { connection: 'Connected', samples: [sample(1)], historyError: false }
  const listeners = new Set<() => void>()
  const start = vi.fn(), stop = vi.fn()
  const live = { getSnapshot: () => state, subscribe: (fn: () => void) => { listeners.add(fn); return () => listeners.delete(fn) }, start, stop } as unknown as LiveTraffic
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ state: 'RUNNING', platform: 'Velocity', platformVersion: '4.3.0', version: 'test' }) }))
  const query = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={query}><App live={live} /></QueryClientProvider>)
  expect(screen.getByText('1 KB/s')).toBeTruthy()
  fireEvent.click(screen.getByLabelText('Pause visualization'))
  act(() => { state = { ...state, samples: [sample(1), sample(2)] }; listeners.forEach(fn => fn()) })
  expect(screen.getByText('1 KB/s')).toBeTruthy(); expect(screen.queryByText('2 KB/s')).toBeNull()
  expect(stop).not.toHaveBeenCalled()
  fireEvent.click(screen.getByLabelText('Resume visualization'))
  expect(screen.getByText('2 KB/s')).toBeTruthy()
  cleanup(); query.clear()
})
