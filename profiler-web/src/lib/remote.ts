export type ViewerSession = { dashboardId: string; viewerSessionId: string; state: 'PENDING' | 'APPROVED'; approvalCode: string | null; csrfToken: string; serverConnected: boolean; bootId: string | null; expiresAt: number | null; lastUpdate: number | null }
let dashboard: string | undefined
let session: ViewerSession | undefined
export function configureRemote(id: string | undefined, viewer?: ViewerSession) { dashboard = id; session = viewer }
export function mutationsUnavailable() { return !!dashboard && (!session?.serverConnected || session.state !== 'APPROVED') }
export function updateViewer(viewer: ViewerSession) { session = viewer }
export function currentViewer() { return session }
export function remotePath(path: string) { return dashboard ? `/api/v1/dashboard/${dashboard}/proxy${path.slice('/api/v1'.length)}` : path }
export function dashboardPath(path: string) { return dashboard ? `/${dashboard}${path === '/' ? '' : path}` : path }
export function localPath() { return dashboard ? window.location.pathname.slice(dashboard.length + 1) || '/' : window.location.pathname }
export function sessionPath(suffix: string) { if (!dashboard) throw new Error('Missing dashboard session'); return `/api/v1/dashboard/${dashboard}/${suffix}` }
export async function apiFetch(path: string, options: RequestInit = {}) {
  if (dashboard && options.method && options.method !== 'GET' && (!session?.serverConnected || session.state !== 'APPROVED')) throw new Error('Minecraft is disconnected or browser approval is missing. Operation was not sent.')
  const headers = new Headers(options.headers)
  if (dashboard && options.method === 'POST') headers.set('X-VNP-CSRF', session?.csrfToken ?? '')
  return fetch(remotePath(path), { ...options, headers, credentials: 'same-origin', cache: 'no-store' })
}
