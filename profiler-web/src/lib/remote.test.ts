import { afterEach, expect, it, vi } from 'vitest'
import { apiFetch, configureRemote, dashboardPath, localPath, mutationsUnavailable, type ViewerSession } from './remote'

export const viewer: ViewerSession = { dashboardId: `00000000-0000-0000-0000-000000000001.${'a'.repeat(48)}`, viewerSessionId: 'browser', state: 'APPROVED', approvalCode: null, csrfToken: 'private-csrf', serverConnected: true, bootId: 'boot-a', expiresAt: null, lastUpdate: 1000 }
afterEach(() => { configureRemote(undefined); window.history.replaceState(null, '', '/'); vi.unstubAllGlobals() })
it('scopes deep links and API paths to the browser dashboard and supplies viewer CSRF', async () => {
  configureRemote(viewer.dashboardId, viewer)
  window.history.replaceState(null, '', `/${viewer.dashboardId}/reports/abc`)
  expect(localPath()).toBe('/reports/abc'); expect(dashboardPath('/settings')).toBe(`/${viewer.dashboardId}/settings`)
  const fetch = vi.fn().mockResolvedValue(new Response('{}')); vi.stubGlobal('fetch', fetch)
  await apiFetch('/api/v1/settings', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-VNP-CSRF': 'old' }, body: '{}' })
  expect(fetch.mock.calls[0][0]).toBe(`/api/v1/dashboard/${viewer.dashboardId}/proxy/settings`)
  expect(fetch.mock.calls[0][1].headers.get('X-VNP-CSRF')).toBe('private-csrf')
  expect(fetch.mock.calls[0][1].credentials).toBe('same-origin')
})
it('never sends mutations while offline or awaiting browser approval, but permits cached history reads', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response('{}')); vi.stubGlobal('fetch', fetch)
  for (const session of [{ ...viewer, serverConnected: false }, { ...viewer, state: 'PENDING' as const }]) {
    configureRemote(viewer.dashboardId, session); expect(mutationsUnavailable()).toBe(true)
    await expect(apiFetch('/api/v1/captures', { method: 'POST', body: '{}' })).rejects.toThrow('Operation was not sent')
  }
  expect(fetch).not.toHaveBeenCalled()
  await apiFetch('/api/v1/traffic/history'); expect(fetch).toHaveBeenCalledOnce()
})
