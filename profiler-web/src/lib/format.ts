export type RateUnit = 'bytes' | 'bits'
export function rate(value: number | null | undefined, unit: RateUnit, exact = false): string {
  if (value == null) return '—'
  let n = unit === 'bits' ? value * 8 : value
  const units = unit === 'bits' ? ['bps', 'Kbps', 'Mbps', 'Gbps'] : ['B/s', 'KB/s', 'MB/s', 'GB/s']
  let scale = 0
  while (n >= 1000 && scale < units.length - 1) { n /= 1000; scale++ }
  return `${n.toLocaleString(undefined, { maximumFractionDigits: exact ? 6 : 2 })} ${units[scale]}`
}
export function total(value: string | null | undefined): string {
  if (value == null) return '—'
  const bytes = BigInt(value)
  const units = ['B', 'KiB', 'MiB', 'GiB']
  let divisor = 1n, scale = 0
  while (bytes >= divisor * 1024n && scale < units.length - 1) { divisor *= 1024n; scale++ }
  if (!scale) return `${bytes.toLocaleString()} B`
  const hundredths = (bytes * 100n + divisor / 2n) / divisor
  return `${(hundredths / 100n).toLocaleString()}.${(hundredths % 100n).toString().padStart(2, '0')} ${units[scale]}`
}
export function uptime(ms: number): string {
  const seconds = Math.floor(ms / 1000)
  return `${Math.floor(seconds / 3600)}h ${Math.floor(seconds / 60) % 60}m ${seconds % 60}s`
}
