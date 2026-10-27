import { expect, it } from 'vitest'
import { filterReport, type PacketReport } from './captures'
import { reportPoints, reportRates, type TimelineSample } from './report-timeline'
import { bandwidthBytes } from './settings'

const alice = { connectionId: 'c1', playerId: 'p1', playerName: 'Alice', serverId: 'lobby' }
const filters = { server: '', player: '', connection: '' }
const sample = (elapsedMs: number, intervalMs: number, inboundBytes: string, outboundBytes: string): TimelineSample => ({ timestamp: elapsedMs, elapsedMs, intervalMs, inboundBytes, outboundBytes, complete: true, dimensions: [], population: null })
const report: PacketReport = { schemaVersion: 3, id: 'r', state: 'COMPLETED', startedAt: 0, endedAt: 2500, durationMs: 2500, detail: '', inboundBytes: '300', outboundBytes: '100', totalBytes: '400', packetCount: '2', cancelledPackets: '0', modifiedPackets: '0', observationErrors: '0', packets: [], scope: { type: 'PLAYER', id: 'p1' }, timeline: { complete: true, samples: [
  { ...sample(0, 0, '0', '0'), population: { global: null, servers: {}, players: {}, connections: {}, complete: false } },
  { ...sample(1500, 1500, '300', '0'), dimensions: [{ context: alice, inboundBytes: '300', outboundBytes: '0' }], population: { global: 2, servers: { lobby: 2, survival: 0 }, players: { p1: 'lobby' }, connections: { c1: 'lobby' }, complete: true } },
  { ...sample(2000, 500, '0', '0') },
  { ...sample(2500, 500, '0', '100'), dimensions: [{ context: { ...alice, serverId: 'survival' }, inboundBytes: '0', outboundBytes: '100' }], population: { global: 2, servers: { lobby: 1, survival: 1 }, players: { p1: 'survival' }, connections: { c1: 'survival' }, complete: true } },
] }, attributions: [
  { context: alice, packets: [{ name: 'PING', protocolState: 'PLAY', direction: 'INBOUND', totalBytes: '300', packetCount: '1', cancelledPackets: '0', modifiedPackets: '0' }] },
  { context: { ...alice, serverId: 'survival' }, packets: [{ name: 'PONG', protocolState: 'PLAY', direction: 'OUTBOUND', totalBytes: '100', packetCount: '1', cancelledPackets: '0', modifiedPackets: '0' }] },
] }
it('uses actual intervals, weighted cumulative mean, idle-zero minima and final partial intervals', () => {
  const points = reportPoints(report, filters)
  expect(reportRates(report, points, 'inbound')).toEqual({ current: 0, mean: 120, min: 0, max: 200 })
  expect(reportRates(report, points, 'outbound')).toEqual({ current: 200, mean: 40, min: 0, max: 200 })
  expect(reportRates(report, points, 'combined')).toEqual({ current: 200, mean: 160, min: 0, max: 200 })
})
it('recalculates temporal traffic and population when filtering old server associations', () => {
  const f = { ...filters, server: 'survival' }, selected = filterReport(report, f), points = reportPoints(report, f)
  expect(points.map(p => p.inbound)).toEqual([0, 0, 0]); expect(points.map(p => p.outbound)).toEqual([0, 0, 200])
  expect(reportRates(selected, points, 'combined').mean).toBe(40)
  expect(points.map(p => p.localPlayers)).toEqual([0, 0, 1])
  expect(reportPoints(report, filters).map(p => p.localPlayers)).toEqual([2, 2, 1]) // Transfer follows current server, not historical packet context.
  expect(points.map(p => p.globalPlayers)).toEqual([2, 2, 2])
})
it('renders partial temporal attribution as unknown, not false zero, and disconnected population as unavailable', () => {
  const broken = structuredClone(report)
  broken.timeline!.complete = false; broken.timeline!.samples[2].complete = false
  broken.timeline!.samples[3].population = { global: 1, servers: { lobby: 1, survival: 0 }, players: {}, connections: {}, complete: true }
  const points = reportPoints(broken, { ...filters, player: 'p1' })
  expect(points[1].inbound).toBeNull(); expect(points[2].localPlayers).toBeNull()
  expect(reportRates(broken, points, 'combined').min).toBeNull()
  expect(reportPoints({ ...report, timeline: null }, filters)).toEqual([])
  const truncated = { ...report, durationMs: 10000 }
  expect(reportRates(truncated, reportPoints(truncated, filters), 'combined').current).toBeNull()
})
it('converts decimal Mbps to bytes without confusing binary totals or the display toggle', () => {
  expect(bandwidthBytes(100)).toBe(12500000); expect(bandwidthBytes(1)).toBe(125000); expect(bandwidthBytes(null)).toBeNull()
})
