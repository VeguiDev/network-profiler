// Test-only WS peer. Credentials arrive over stdin, never process arguments or logs.
import { createRequire } from 'node:module'
import { createInterface } from 'node:readline'
const require = createRequire(new URL('../profiler-backend/package.json', import.meta.url))
const WebSocket = require('ws')
const lines = createInterface({ input: process.stdin })
lines.once('line', line => {
  const { url, cookie, origin } = JSON.parse(line)
  const ws = new WebSocket(url, { origin, headers: { Cookie: cookie } })
  const timer = setInterval(() => { if (ws.readyState === 1) ws.send(JSON.stringify({ type: 'heartbeat' })) }, 10000)
  ws.on('open', () => console.log('CONNECTED'))
  ws.on('error', () => { clearInterval(timer); process.exit(1) })
  ws.on('close', () => { clearInterval(timer); process.exit(0) })
  lines.on('close', () => { clearInterval(timer); ws.close(); setTimeout(() => process.exit(0), 100).unref() })
})
