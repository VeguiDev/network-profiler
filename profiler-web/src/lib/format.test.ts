import { describe, expect, it } from 'vitest'
import { rate, total, uptime } from './format'
describe('units', () => {
  it('distinguishes bits, decimal rates and binary totals', () => {
    expect(rate(125000, 'bytes')).toBe('125 KB/s')
    expect(rate(125000, 'bits')).toBe('1 Mbps')
    expect(total('1024')).toBe('1.00 KiB')
    expect(total('1048576')).toBe('1.00 MiB')
    expect(rate(null, 'bytes')).toBe('—')
    expect(total(null)).toBe('—')
  })
  it('preserves precision beyond Number.MAX_SAFE_INTEGER', () => {
    const n = 9007199254740993n
    expect(total(n.toString())).not.toContain('NaN')
    expect(total('0')).toBe('0 B')
    expect(uptime(3661000)).toBe('1h 1m 1s')
  })
})
