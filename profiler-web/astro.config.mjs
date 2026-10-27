import { defineConfig } from 'astro/config'
import react from '@astrojs/react'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath } from 'node:url'

// Static client-only shell. Both the Hono Node adapter and Workers Assets provide SPA fallback.
export default defineConfig({ integrations: [react()], vite: { plugins: [tailwindcss()], resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } } } })
