import { expect, it } from 'vitest'
import { filterReport, packetRows, percentage, type PacketReport } from './captures'

export const report: PacketReport = { schemaVersion: 1, id: 'capture', state: 'RUNNING', startedAt: 1000, endedAt: null, durationMs: 5000, detail: 'Recording', inboundBytes: '150', outboundBytes: '100', totalBytes: '250', packetCount: '3', cancelledPackets: '0', modifiedPackets: '0', observationErrors: '0', packets: [
  { name: 'PING', protocolState: 'STATUS', direction: 'INBOUND', totalBytes: '150', packetCount: '2', cancelledPackets: '0', modifiedPackets: '0' },
  { name: 'PONG', protocolState: 'STATUS', direction: 'OUTBOUND', totalBytes: '100', packetCount: '1', cancelledPackets: '0', modifiedPackets: '0' },
] }
it('sorts logical types by accumulated bytes and filters direction and state without changing denominators', () => {
  expect(packetRows(report, 'BOTH').map(r => r.name)).toEqual(['PING', 'PONG'])
  expect(packetRows(report, 'INBOUND')).toHaveLength(1)
  expect(packetRows(report, 'OUTBOUND', 'status')[0].name).toBe('PONG')
  expect(percentage('150', report.totalBytes)).toBe(60)
  expect(percentage('150', report.inboundBytes)).toBe(100)
  expect(percentage('0', '0')).toBe(0)
})
it('does not lose precision when calculating large byte percentages', () => {
  expect(percentage('900719925474099300', '1801439850948198600')).toBe(50)
})
it('filters immutable attribution by server and player and preserves historic associations', () => {
  const source: PacketReport = { ...report, schemaVersion: 2, attributionComplete: true, attributions: [
    { context: { connectionId: 'a', playerId: 'alice', playerName: 'Alice', serverId: 'lobby' }, packets: [report.packets[0]] },
    { context: { connectionId: 'a', playerId: 'alice', playerName: 'Alice', serverId: 'survival' }, packets: [report.packets[1]] },
  ] }
  expect(filterReport(source, { server: 'lobby', player: '', connection: '' }).totalBytes).toBe('150')
  expect(filterReport(source, { server: '', player: 'alice', connection: 'a' }).totalBytes).toBe('250')
  expect(filterReport(source, { server: 'survival', player: 'bob', connection: '' }).totalBytes).toBe('0')
  expect(source.attributions?.[0].context.serverId).toBe('lobby')
  expect(filterReport(report, { server: '', player: '', connection: '' })).toBe(report)
})
