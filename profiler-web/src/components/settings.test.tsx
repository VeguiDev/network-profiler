import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { SettingsPage } from './settings'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })
it('saves separate shared visual references with a fresh CSRF token and supports disabling', async () => {
  const query = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const fetch = vi.fn(async (_url: string, options?: RequestInit) => ({ ok: true, json: async () => ({ bandwidth: options?.method === 'POST' ? JSON.parse(options.body as string) : { inboundMbps: null, outboundMbps: null }, csrfToken: 'fresh', persistent: true }) }))
  vi.stubGlobal('fetch', fetch)
  render(<QueryClientProvider client={query}><SettingsPage /></QueryClientProvider>)
  await waitFor(() => expect(screen.getByText('Save settings').hasAttribute('disabled')).toBe(false))
  fireEvent.change(screen.getByLabelText('Inbound maximum (Mbps)'), { target: { value: '100' } })
  fireEvent.change(screen.getByLabelText('Outbound maximum (Mbps)'), { target: { value: '50' } })
  fireEvent.click(screen.getByText('Save settings'))
  await screen.findByText('Settings saved for all dashboard users.')
  const post = fetch.mock.calls.find(c => c[1]?.method === 'POST')!
  expect(post[1]?.headers).toMatchObject({ 'X-MNP-CSRF': 'fresh' }); expect(JSON.parse(post[1]?.body as string)).toEqual({ inboundMbps: 100, outboundMbps: 50 })
  fireEvent.change(screen.getByLabelText('Inbound maximum (Mbps)'), { target: { value: '' } })
  fireEvent.click(screen.getByText('Save settings'))
  await waitFor(() => expect(query.getQueryData(['settings'])).toMatchObject({ bandwidth: { inboundMbps: null, outboundMbps: 50 } }))
  cleanup(); query.clear()
})
