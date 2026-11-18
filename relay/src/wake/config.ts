import { ConfigError } from '../config.ts'

export interface WakeConfig {
  /** 0 = aus */
  port: number
  bindHost: string
  apiUrl: string
  hostingDomain: string
  /** signiert die API-Anfragen (erster Eintrag aus RELAY_SECRET) */
  secret: string
  maxConnections: number
  maxConnsPerIp: number
  newConnsPerIpPerMin: number
  /** Spieler, die gerade in der Konfigurationsphase auf ihren Server warten */
  maxWaiting: number
  maxPacket: number
  handshakeTimeoutMs: number
  ackTimeoutMs: number
  keepAliveMs: number
  pollMs: number
  giveUpMs: number
  /** Konfigurationsphase: so lange ohne Byte vom Client → zu */
  configIdleMs: number
  /** nach dem Transfer, falls der Client nicht selbst trennt */
  transferLingerMs: number
  resolveCacheMs: number
  apiTimeoutMs: number
}

export const WAKE_DEFAULTS: Omit<WakeConfig, 'secret' | 'bindHost'> = {
  port: 25565,
  apiUrl: 'https://trs-launcher.theredstonee.de',
  hostingDomain: 'trs-launcher.de',
  maxConnections: 1000,
  maxConnsPerIp: 10,
  newConnsPerIpPerMin: 30,
  maxWaiting: 200,
  maxPacket: 32 * 1024,
  handshakeTimeoutMs: 5000,
  ackTimeoutMs: 5000,
  keepAliveMs: 10_000,
  pollMs: 3000,
  giveUpMs: 150_000,
  configIdleMs: 30_000,
  transferLingerMs: 10_000,
  resolveCacheMs: 10_000,
  apiTimeoutMs: 5000,
}

/** https überall, http nur für lokale Tests auf 127.0.0.1. Ohne Slash am Ende. */
export function parseApiUrl(raw: string | undefined): string {
  const s = (raw?.trim() || WAKE_DEFAULTS.apiUrl).replace(/\/+$/, '')
  let u: URL
  try {
    u = new URL(s)
  } catch {
    throw new ConfigError('Invalid configuration: TRS_API_URL (expected a URL)')
  }
  const ok = u.protocol === 'https:' || (u.protocol === 'http:' && u.hostname === '127.0.0.1')
  if (!ok || u.username || u.password || u.search || u.hash) {
    throw new ConfigError('Invalid configuration: TRS_API_URL (expected https://…)')
  }
  return s
}

export function loadWakeConfig(env: Record<string, string | undefined>, secrets: string[], bindHost: string): WakeConfig {
  const rawPort = env.WAKE_PORT?.trim()
  let port = WAKE_DEFAULTS.port
  if (rawPort) {
    if (!/^\d+$/.test(rawPort) || Number(rawPort) > 65535) throw new ConfigError('Invalid configuration: WAKE_PORT (expected 0-65535)')
    port = Number(rawPort)
  }
  const domain = (env.HOSTING_DOMAIN?.trim() || WAKE_DEFAULTS.hostingDomain).toLowerCase().replace(/\.$/, '')
  if (!/^[a-z0-9-]+(\.[a-z0-9-]+)+$/.test(domain)) throw new ConfigError('Invalid configuration: HOSTING_DOMAIN')
  return {
    ...WAKE_DEFAULTS,
    port,
    bindHost,
    apiUrl: parseApiUrl(env.TRS_API_URL),
    hostingDomain: domain,
    secret: secrets[0]!,
  }
}

/** Für Tests. */
export function testWakeConfig(over: Partial<WakeConfig> & { secret: string, apiUrl: string }): WakeConfig {
  return { ...WAKE_DEFAULTS, bindHost: '127.0.0.1', port: 0, ...over }
}
