import { getJson } from './types'
import { apiFetch, currentViewer } from './remote'
export type Bandwidth = { inboundMbps: number | null; outboundMbps: number | null }
export type Settings = { bandwidth: Bandwidth; csrfToken: string; persistent: boolean }
export const bandwidthBytes = (mbps: number | null | undefined) => mbps == null ? null : mbps * 1_000_000 / 8
export async function saveSettings(bandwidth: Bandwidth): Promise<Settings> {
  const token = currentViewer()?.csrfToken ?? (await getJson<Settings>('/api/v1/settings')).csrfToken
  const response = await apiFetch('/api/v1/settings', { method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-VNP-CSRF': token }, body: JSON.stringify(bandwidth) })
  if (!response.ok) throw new Error(`Could not save settings (${response.status})`)
  return response.json() as Promise<Settings>
}
