import { useEffect, useState, useSyncExternalStore } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from './App'
import { LiveTraffic } from './lib/live'
import { configureRemote, sessionPath, type ViewerSession } from './lib/remote'

const pattern = /^[a-f0-9-]{36}\.[a-f0-9]{48}$/
export default function Dashboard() {
  const [query] = useState(() => new QueryClient({ defaultOptions: { queries: { refetchOnWindowFocus: false, retry: false }, mutations: { retry: false } } }))
  const [live] = useState(() => new LiveTraffic(query))
  const [error, setError] = useState(''), [ready, setReady] = useState(false)
  const state = useSyncExternalStore(live.subscribe, live.getSnapshot)
  const id = window.location.pathname.split('/')[1]
  useEffect(() => {
    if (!pattern.test(id)) return
    let cancelled = false
    configureRemote(id)
    void fetch(sessionPath('viewer'), { method: 'POST', credentials: 'same-origin' }).then(async response => {
      if (!response.ok) throw new Error(response.status === 410 ? 'This dashboard has expired. Run /vnp dashboard for a new link.' : 'Could not open this browser session.')
      const viewer = await response.json() as ViewerSession
      if (cancelled) return
      configureRemote(id, viewer); live.setViewer(viewer); live.start(); setReady(true)
    }).catch(e => { if (!cancelled) setError(e.message) })
    return () => { cancelled = true; live.stop(); query.clear() }
  }, [id, live, query])
  if (!pattern.test(id)) return <Gate title="Vegui Network Profiler (VNP)"><p>Run <code>/vnp dashboard</code> in your Minecraft proxy to create a private dashboard link.</p><p>Each browser requires approval with <code>/vnp trustSession &lt;code&gt;</code>. No inbound Minecraft ports are needed.</p></Gate>
  if (error || state.expired) return <Gate title="Dashboard unavailable"><p>{error || 'This browser session expired. Reload to request a new approval, or run /vnp dashboard for a new link.'}</p></Gate>
  if (!ready || !state.viewer) return <Gate title="Connecting…"><p>Creating a browser-specific approval request.</p></Gate>
  if (state.viewer.state !== 'APPROVED') return <Gate title="Approve this browser"><p>Run this command in your proxy console or as a permitted administrator:</p><pre className="overflow-auto rounded-lg bg-muted p-4 text-primary">/vnp trustSession {state.viewer.approvalCode}</pre><p>Approval grants full dashboard access, including captures and shared settings. This request lasts 10 minutes. A different browser needs its own approval.</p><p>{state.viewer.serverConnected ? 'Waiting for Minecraft approval…' : 'Minecraft is disconnected. Reconnect the proxy to approve this browser.'}</p></Gate>
  return <QueryClientProvider client={query}><App live={live} /></QueryClientProvider>
}
function Gate({ title, children }: { title: string; children: React.ReactNode }) {
  return <main className="flex min-h-screen items-center justify-center bg-background p-6 text-foreground"><section className="w-full max-w-xl space-y-5 rounded-xl border border-border bg-card p-7 text-sm leading-relaxed text-muted-foreground"><h1 className="text-2xl font-semibold text-foreground">{title}</h1>{children}</section></main>
}
