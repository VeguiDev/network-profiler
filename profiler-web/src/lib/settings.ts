import { getJson } from './types'
export type Bandwidth = { inboundMbps: number | null; outboundMbps: number | null }
export type Settings = { bandwidth: Bandwidth; csrfToken: string; persistent: boolean }
export const bandwidthBytes = (mbps: number | null | undefined) => mbps == null ? null : mbps * 1_000_000 / 8
export async function saveSettings(bandwidth: Bandwidth): Promise<Settings> {
  const current = await getJson<Settings>('/api/v1/settings')
  const response = await fetch('/api/v1/settings', { method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', 'X-MNP-CSRF': current.csrfToken }, body: JSON.stringify(bandwidth) })
  if (!response.ok) throw new Error(`Could not save settings (${response.status})`)
  return response.json() as Promise<Settings>
}
