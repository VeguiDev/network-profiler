import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from './App'
import { LiveTraffic } from './lib/live'
import './index.css'
const query = new QueryClient({ defaultOptions: { queries: { refetchOnWindowFocus: false } } })
const live = new LiveTraffic(query)
createRoot(document.getElementById('root')!).render(<StrictMode><QueryClientProvider client={query}><App live={live} /></QueryClientProvider></StrictMode>)
