import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { getJson } from '@/lib/types'
import { saveSettings, type Settings } from '@/lib/settings'
import { Card } from './ui/card'
import { Button } from './ui/button'

export function SettingsPage() {
  const query = useQueryClient()
  const { data, isError } = useQuery({ queryKey: ['settings'], queryFn: ({ signal }) => getJson<Settings>('/api/v1/settings', signal) })
  const [inbound, setInbound] = useState(''), [outbound, setOutbound] = useState(''), [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false), [message, setMessage] = useState('')
  useEffect(() => { if (data && !dirty) { setInbound(String(data.bandwidth?.inboundMbps ?? '')); setOutbound(String(data.bandwidth?.outboundMbps ?? '')) } }, [data, dirty])
  const save = async () => {
    setBusy(true); setMessage('')
    try {
      const parse = (s: string) => { const n = s.trim() ? Number(s) : null; if (n !== null && (!Number.isFinite(n) || n <= 0 || n > 1_000_000)) throw new Error('Enter a positive Mbps value up to 1000000, or leave empty.'); return n }
      const saved = await saveSettings({ inboundMbps: parse(inbound), outboundMbps: parse(outbound) })
      query.setQueryData(['settings'], saved); setDirty(false); setMessage('Settings saved for all dashboard users.')
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Save failed') } finally { setBusy(false) }
  }
  return <div className="space-y-6"><div><h1 className="text-3xl font-semibold">Settings</h1><p className="mt-2 text-sm text-muted-foreground">Shared dashboard preferences, stored on the server.</p></div><Card className="max-w-2xl space-y-5 p-6"><h2 className="font-medium">Visual bandwidth references</h2><p className="text-sm text-muted-foreground">Independent IN / OUT reference lines in Overview and report charts. This never throttles traffic, clips measured values, or changes collection. Leave empty to disable. Mbps uses decimal bits per second.</p>{[['Inbound maximum (Mbps)', inbound, setInbound], ['Outbound maximum (Mbps)', outbound, setOutbound]].map(([label, value, setter]) => <label key={String(label)} className="block space-y-2 text-sm"><span>{String(label)}</span><input aria-label={String(label)} type="number" min="0.000001" max="1000000" step="any" value={String(value)} onChange={e => { (setter as (v: string) => void)(e.target.value); setDirty(true) }} placeholder="Disabled" className="block h-10 w-full rounded-lg border border-input bg-background px-3" /></label>)}<Button disabled={!data || busy} onClick={() => void save()}>{busy ? 'Saving…' : 'Save settings'}</Button>{(isError || message) && <p role="status" className="text-sm">{isError ? 'Settings unavailable. No changes have been saved.' : message}</p>}<p className="text-xs text-muted-foreground">All users with dashboard access can edit these values. Protect the entire dashboard with an authenticated reverse proxy.</p></Card></div>
}
