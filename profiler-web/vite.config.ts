import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    proxy: { '/api': { target: 'http://127.0.0.1:8765', ws: true, changeOrigin: true,
      configure(proxy) {
        proxy.on('proxyReqWs', req => req.removeHeader('origin'))
        // Dev server is loopback-only; normalize same-origin browser POSTs for the API proxy.
        proxy.on('proxyReq', req => { if (req.method === 'POST') req.setHeader('Origin', 'http://127.0.0.1:8765') })
      } } },
  },
  test: { environment: 'jsdom', restoreMocks: true },
})
