import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CaptureControls, PacketReports } from './packet-reports'
import type { CaptureIndex, PacketReport } from '@/lib/captures'

const report: PacketReport = { schemaVersion: 1, id: 'abc', state: 'RUNNING', startedAt: 1000, endedAt: null, durationMs: 5000, detail: 'Recording', inboundBytes: '150', outboundBytes: '100', totalBytes: '250', packetCount: '3', cancelledPackets: '0', modifiedPackets: '0', observationErrors: '0', packets: [
  { name: 'PING', protocolState: 'STATUS', direction: 'INBOUND', totalBytes: '150', packetCount: '2', cancelledPackets: '0', modifiedPackets: '0' },
  { name: 'PONG', protocolState: 'STATUS', direction: 'OUTBOUND', totalBytes: '100', packetCount: '1', cancelledPackets: '0', modifiedPackets: '0' },
] }
const index: CaptureIndex = { available: true, detail: 'Ready', csrfToken: 'token', maxDurationSeconds: 900, reports: [] }
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
it('starts on demand with CSRF and opens the live report', async () => {
  const navigate = vi.fn(), query = new QueryClient()
  const fetch = vi.fn(async (_url: string, options?: RequestInit) => ({ ok: true, json: async () => options?.method === 'POST' ? report : index }))
  vi.stubGlobal('fetch', fetch)
  render(<QueryClientProvider client={query}><CaptureControls index={index} navigate={navigate} /></QueryClientProvider>)
  fireEvent.click(screen.getByText('Start analysis'))
  await waitFor(() => expect(navigate).toHaveBeenCalledWith('/reports/abc'))
  expect(fetch.mock.calls[1][1]).toMatchObject({ method: 'POST', headers: { 'X-MNP-CSRF': 'token', 'Content-Type': 'application/json' } })
  cleanup(); query.clear()
})
it('shows accumulated byte shares per direction and finalizes without inventing transport totals', async () => {
  const query = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => ({ ok: true, json: async () => url === '/api/v1/captures' ? index : options?.method === 'POST' ? { ...report, state: 'COMPLETED', endedAt: 6000 } : report })))
  render(<QueryClientProvider client={query}><PacketReports id="abc" index={index} navigate={vi.fn()} /></QueryClientProvider>)
  await screen.findByText('PING'); expect(screen.getByText('60.00%')).toBeTruthy()
  fireEvent.click(screen.getByRole('tab', { name: 'Inbound' }))
  expect(screen.queryByText('PONG')).toBeNull(); expect(screen.getByText('100.00%')).toBeTruthy()
  fireEvent.click(screen.getByRole('tab', { name: 'Outbound' }))
  expect(screen.queryByText('PING')).toBeNull(); expect(screen.getByText('PONG')).toBeTruthy()
  fireEvent.click(screen.getByText('Stop & save report'))
  await screen.findByText('COMPLETED'); expect(screen.queryByText('Stop & save report')).toBeNull()
  expect(screen.getByText(/OUT does not confirm a successful write/)).toBeTruthy()
  cleanup(); query.clear()
})
