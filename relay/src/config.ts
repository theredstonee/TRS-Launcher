/** Laufzeit-Konfiguration – ausschließlich aus Umgebungsvariablen (.env über start.sh). */
export interface RelayConfig {
  /** HMAC-Schlüssel (gleich wie `RELAY_SECRET` der API). Der erste signiert nichts – alle prüfen (Schlüsseltausch). */
  secrets: string[]
  bindHost: string
  tcpPort: number
  udpPort: number
  /** Nur zur Anzeige im Log (die API gibt den Clients Host + Ports). */
  publicHost: string
  /** Spieler je Raum inkl. Host (Obergrenze, der Token kann weniger erlauben). */
  maxRoomPlayers: number
  /** Gleichzeitige TCP-Verbindungen je Gast-UUID und Raum (Server-Ping + Login). */
  maxConnsPerGuest: number
  /** TCP-Verbindungen insgesamt. */
  maxConnections: number
  /** Gleichzeitige TCP-Verbindungen je IP. */
  maxConnsPerIp: number
  /** Neue TCP-Verbindungen je IP und Minute. */
  newConnsPerIpPerMin: number
  /** Fehlgeschlagene Handshakes (falscher Token usw.) je IP in 10 min, danach Ablehnung. */
  handshakeFailsPerIp: number
  /** Bandbreite je Raum (alle Richtungen, TCP + UDP), Mbit/s. */
  roomBandwidthMbit: number
  handshakeTimeoutMs: number
  pairTimeoutMs: number
  controlIdleMs: number
  pipeIdleMs: number
  /** Gast-Bytes vor der Kopplung (werden danach weitergereicht). */
  guestBufferMax: number
  udpBindingTtlMs: number
  /** STUN-Antworten je IP und Sekunde. */
  stunPerIpPerSec: number
  /** UDP-BINDs je IP und Minute. */
  udpBindsPerIpPerMin: number
  shutdownGraceMs: number
  statsIntervalMs: number
  /** Erlaubte Uhrabweichung zur API (Sekunden). */
  clockSkewSec: number
}

export const DEFAULTS: Omit<RelayConfig, 'secrets'> = {
  bindHost: '0.0.0.0',
  tcpPort: 25503,
  udpPort: 25504,
  publicHost: '',
  maxRoomPlayers: 10,
  maxConnsPerGuest: 3,
  maxConnections: 1000,
  maxConnsPerIp: 20,
  newConnsPerIpPerMin: 60,
  handshakeFailsPerIp: 30,
  roomBandwidthMbit: 20,
  handshakeTimeoutMs: 10_000,
  pairTimeoutMs: 10_000,
  controlIdleMs: 45_000,
  pipeIdleMs: 5 * 60_000,
  guestBufferMax: 64 * 1024,
  udpBindingTtlMs: 30_000,
  stunPerIpPerSec: 20,
  udpBindsPerIpPerMin: 30,
  shutdownGraceMs: 5_000,
  statsIntervalMs: 10 * 60_000,
  clockSkewSec: 5,
}

export class ConfigError extends Error {}

function int(env: Record<string, string | undefined>, key: string, fallback: number, min: number, max: number): number {
  const raw = env[key]?.trim()
  if (raw === undefined || raw === '') return fallback
  if (!/^\d+$/.test(raw)) throw new ConfigError(`Invalid configuration: ${key} (expected an integer)`)
  const n = Number(raw)
  if (n < min || n > max) throw new ConfigError(`Invalid configuration: ${key} (expected ${min}-${max})`)
  return n
}

function num(env: Record<string, string | undefined>, key: string, fallback: number, min: number, max: number): number {
  const raw = env[key]?.trim()
  if (raw === undefined || raw === '') return fallback
  if (!/^\d+(\.\d+)?$/.test(raw)) throw new ConfigError(`Invalid configuration: ${key} (expected a number)`)
  const n = Number(raw)
  if (n < min || n > max) throw new ConfigError(`Invalid configuration: ${key} (expected ${min}-${max})`)
  return n
}

/** `RELAY_SECRET` = kommagetrennt, jeder ≥ 32 Zeichen. Fehlermeldungen nennen nie Werte. */
export function parseSecrets(raw: string | undefined): string[] {
  const parts = (raw ?? '').split(',').map((s) => s.trim()).filter(Boolean)
  if (parts.length === 0) throw new ConfigError('Invalid configuration: RELAY_SECRET (required)')
  if (parts.some((s) => s.length < 32)) throw new ConfigError('Invalid configuration: RELAY_SECRET (each secret needs at least 32 characters)')
  return parts
}

export function loadConfig(env: Record<string, string | undefined>): RelayConfig {
  const port = (key: string, fallback: number) => int(env, key, fallback, 0, 65535)
  const tcpFallback = env.SERVER_PORT?.trim() ? port('SERVER_PORT', DEFAULTS.tcpPort) : DEFAULTS.tcpPort
  const bindHost = env.BIND_HOST?.trim() || DEFAULTS.bindHost
  if (!/^[0-9a-fA-F.:]+$/.test(bindHost)) throw new ConfigError('Invalid configuration: BIND_HOST (expected an IP address)')
  return {
    secrets: parseSecrets(env.RELAY_SECRET),
    bindHost,
    tcpPort: port('TCP_PORT', tcpFallback),
    udpPort: port('UDP_PORT', DEFAULTS.udpPort),
    publicHost: env.PUBLIC_HOST?.trim() ?? '',
    maxRoomPlayers: int(env, 'MAX_ROOM_PLAYERS', DEFAULTS.maxRoomPlayers, 2, 10),
    maxConnsPerGuest: int(env, 'MAX_CONNS_PER_GUEST', DEFAULTS.maxConnsPerGuest, 1, 10),
    maxConnections: int(env, 'MAX_CONNECTIONS', DEFAULTS.maxConnections, 1, 100_000),
    maxConnsPerIp: int(env, 'MAX_CONNS_PER_IP', DEFAULTS.maxConnsPerIp, 1, 10_000),
    newConnsPerIpPerMin: int(env, 'NEW_CONNS_PER_IP_PER_MIN', DEFAULTS.newConnsPerIpPerMin, 1, 100_000),
    handshakeFailsPerIp: int(env, 'HANDSHAKE_FAILS_PER_IP', DEFAULTS.handshakeFailsPerIp, 1, 100_000),
    roomBandwidthMbit: num(env, 'ROOM_BANDWIDTH_MBIT', DEFAULTS.roomBandwidthMbit, 0.1, 10_000),
    handshakeTimeoutMs: DEFAULTS.handshakeTimeoutMs,
    pairTimeoutMs: DEFAULTS.pairTimeoutMs,
    controlIdleMs: DEFAULTS.controlIdleMs,
    pipeIdleMs: int(env, 'PIPE_IDLE_SEC', DEFAULTS.pipeIdleMs / 1000, 10, 86_400) * 1000,
    guestBufferMax: DEFAULTS.guestBufferMax,
    udpBindingTtlMs: DEFAULTS.udpBindingTtlMs,
    stunPerIpPerSec: int(env, 'STUN_PER_IP_PER_SEC', DEFAULTS.stunPerIpPerSec, 1, 10_000),
    udpBindsPerIpPerMin: int(env, 'UDP_BINDS_PER_IP_PER_MIN', DEFAULTS.udpBindsPerIpPerMin, 1, 100_000),
    shutdownGraceMs: DEFAULTS.shutdownGraceMs,
    statsIntervalMs: DEFAULTS.statsIntervalMs,
    clockSkewSec: DEFAULTS.clockSkewSec,
  }
}

/** Für Tests: Standardwerte + Überschreibungen. */
export function testConfig(over: Partial<RelayConfig> & { secrets: string[] }): RelayConfig {
  return { ...DEFAULTS, bindHost: '127.0.0.1', tcpPort: 0, udpPort: 0, ...over }
}
