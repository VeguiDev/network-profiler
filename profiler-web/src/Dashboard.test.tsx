import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import Dashboard from './Dashboard'
import { configureRemote } from './lib/remote'

vi.mock('./App', () => ({ default: () => <div>Approved application</div> }))
const dashboardId = `00000000-0000-0000-0000-000000000001.${'a'.repeat(48)}`
class Socket {
  static current: Socket
  onmessage?: (e: { data: string }) => void
  constructor() { Socket.current = this }
  close() {}
}
afterEach(() => { cleanup(); configureRemote(undefined); window.history.replaceState(null, '', '/'); vi.unstubAllGlobals() })
it('does not mount metrics UI before this browser is approved', async () => {
  const viewer = { dashboardId, viewerSessionId: 'browser', state: 'PENDING', approvalCode: 'abcd1234', csrfToken: 'csrf', serverConnected: true, bootId: null, expiresAt: null, lastUpdate: null }
  const fetch = vi.fn(async () => ({ ok: true, json: async () => viewer })); vi.stubGlobal('fetch', fetch); vi.stubGlobal('WebSocket', Socket)
  window.history.replaceState(null, '', `/${dashboardId}/reports/report`)
  render(<Dashboard />)
  await screen.findByText('/vnp trustSession abcd1234')
  expect(screen.queryByText('Approved application')).toBeNull(); expect(fetch).toHaveBeenCalledOnce()
  Socket.current.onmessage?.({ data: JSON.stringify({ type: 'relay.state', ...viewer, state: 'APPROVED', approvalCode: null }) })
  await waitFor(() => expect(screen.getByText('Approved application')).toBeTruthy())
})
