import type { RelayConfig } from './config.ts'
import { RoomRegistry } from './rooms.ts'
import { TcpRelay } from './tcp.ts'
import { UdpRelay } from './udp.ts'

export interface Relay {
  tcpPort: number
  udpPort: number
  registry: RoomRegistry
  tcp: TcpRelay
  udp: UdpRelay
  stats: () => { rooms: number, connections: number, pairs: number, udpBindings: number, tcpBytes: number, udpBytes: number, rejected: number }
  close: () => Promise<void>
}

export interface RelayOptions {
  now?: () => number
  /** Log-Ausgabe. Nie Nutzdaten, Tokens oder vollständige IPs übergeben. */
  log?: (msg: string) => void
}

/** Startet TCP- und UDP-Teil auf den konfigurierten Ports (0 = frei, für Tests). */
export async function startRelay(config: RelayConfig, opts: RelayOptions = {}): Promise<Relay> {
  const now = opts.now ?? Date.now
  const log = opts.log ?? ((m: string) => console.info(`[trs-relay] ${m}`))
  const registry = new RoomRegistry(config.roomBandwidthMbit)
  const deps = { config, registry, now, log }
  const tcp = new TcpRelay(deps)
  const udp = new UdpRelay(deps)
  const tcpPort = await tcp.listen()
  let udpPort: number
  try {
    udpPort = await udp.listen()
  } catch (err) {
    await tcp.close()
    throw err
  }
  const stats = () => ({
    rooms: registry.rooms.size,
    connections: tcp.connectionCount,
    pairs: tcp.pairs.size,
    udpBindings: registry.udpByKey.size,
    tcpBytes: tcp.bytes,
    udpBytes: udp.bytes,
    rejected: tcp.rejected,
  })
  const timers = [
    setInterval(() => {
      const s = stats()
      log(`stats rooms=${s.rooms} conns=${s.connections} pairs=${s.pairs} udp=${s.udpBindings} tcpBytes=${s.tcpBytes} udpBytes=${s.udpBytes} rejected=${s.rejected}`)
    }, config.statsIntervalMs),
    setInterval(() => {
      tcp.sweepLimiters()
      udp.sweepLimiters()
    }, 60_000),
  ]
  for (const t of timers) t.unref()
  let closing: Promise<void> | null = null
  return {
    tcpPort,
    udpPort,
    registry,
    tcp,
    udp,
    stats,
    close: () => {
      closing ??= (async () => {
        for (const t of timers) clearInterval(t)
        await Promise.all([tcp.close(), udp.close()])
      })()
      return closing
    },
  }
}
