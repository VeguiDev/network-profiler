import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { EntityStatistics, type StatisticsPage } from './entity-statistics'
import type { CaptureIndex } from '@/lib/captures'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })
const data: StatisticsPage = { available: true, detail: 'Ready', timestamp: 1790000000000, intervalMs: 30000, ready: true, complete: true, observationErrors: '0', untrackedBytes: '0', intervalInboundBytes: '150', intervalOutboundBytes: '350', total: 1, page: 0, pageSize: 50, rows: [
  { id: 'lobby', playerId: null, playerName: null, serverId: 'lobby', active: true, connections: 1, inboundBytes: '150', outboundBytes: '0', totalBytes: '150', intervalInboundBytes: '150', intervalOutboundBytes: '0', inboundBytesPerSecond: 5, outboundBytesPerSecond: 0 },
] }
it('shows full-interval percentages, switches directions and opens details', async () => {
  const query = new QueryClient({ defaultOptions: { queries: { retry: false } } }), navigate = vi.fn()
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => data })))
  render(<QueryClientProvider client={query}><EntityStatistics path="/servers" navigate={navigate} /></QueryClientProvider>)
  await screen.findByText('30%')
  fireEvent.click(screen.getByRole('button', { name: 'Inbound' })); expect(screen.getByText('100%')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Details' })); expect(navigate).toHaveBeenCalledWith('/servers/lobby')
  cleanup(); query.clear()
})
it('starts a capture with the selected server scope rather than collecting globally', async () => {
  const query = new QueryClient({ defaultOptions: { queries: { retry: false } } }), navigate = vi.fn()
  const index: CaptureIndex = { available: true, detail: 'Ready', csrfToken: 'token', maxDurationSeconds: 900, reports: [] }
  const fetch = vi.fn(async (url: string, options?: RequestInit) => ({ ok: true, json: async () => options?.method === 'POST' ? { id: 'scoped' } : url === '/api/v1/captures' ? index : data }))
  vi.stubGlobal('fetch', fetch)
  render(<QueryClientProvider client={query}><EntityStatistics path="/servers/lobby" index={index} navigate={navigate} /></QueryClientProvider>)
  await screen.findByText('30%')
  fireEvent.click(screen.getByText('Start analysis'))
  await waitFor(() => expect(navigate).toHaveBeenCalledWith('/reports/scoped'))
  const mutation = fetch.mock.calls.find(call => call[1]?.method === 'POST')!
  expect(JSON.parse(mutation[1]!.body as string)).toEqual({ scope: { type: 'SERVER', id: 'lobby' } })
  cleanup(); query.clear()
})
