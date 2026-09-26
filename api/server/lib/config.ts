import { hkdfSync } from 'node:crypto'
import { z } from 'zod'
import { normalizeUuid } from './ids'

/** Laufzeit-Konfiguration – ausschließlich aus Umgebungsvariablen (.env). */
export interface Config {
  dataDir: string
  publicBaseUrl: string
  /** Adresse der Website (Links, Weiterleitungen, Sitemap). */
  siteUrl: string
  /** Hosts, die nur die API bedienen (Seiten dort → 301 zur Website), klein geschrieben. */
  apiOnlyHosts: ReadonlySet<string>
  adminUuids: ReadonlySet<string>
  adminApiKey: string | null
  secretKey: string
  corsOrigins: ReadonlySet<string>
  trustProxy: 'cloudflare' | 'none'
  mojangSessionUrl: string
  mojangApiUrl: string
  logRequests: boolean
  /**
   * Schlüssel für die Verschlüsselung der Chat-Inhalte (AES-256-GCM). Der erste ist aktiv (neue Daten),
   * die übrigen nur zum Entschlüsseln alter Daten (Schlüsseltausch, s. API.md §18.9). Leer = aus
   * SECRET_KEY abgeleitet (`derived: true`).
   */
  chatKeys: { keys: { id: string, key: Buffer }[], derived: boolean }
  /** Höchstens so viele Bytes Chat-Bilder insgesamt (Plattenplatz), `CHAT_STORAGE_MAX_MB`. */
  chatStorageMaxBytes: number
  /** Server-Einladungen: Status vom Server abfragen (Ping mit SSRF-Schutz). `SERVER_PING=false` schaltet ab. */
  serverPing: boolean
  /** Welt-Hosting (§21): Relay-Adresse + gemeinsames Geheimnis. `null` = Hosting aus (503 hosting_unavailable). */
  hosting: HostingConfig | null
  limits: Limits
}

export interface HostingConfig {
  /** Öffentlicher Name/IP des Relays (TCP + UDP). */
  relayHost: string
  relayTcpPort: number
  relayUdpPort: number
  /** HMAC-Schlüssel für Relay-Tokens; der erste signiert (weitere nur fürs Relay beim Schlüsseltausch). */
  relaySecrets: string[]
  /** STUN-Server `host:port`, die Clients für ihre öffentliche Adresse fragen. */
  stun: string[]
}

export interface Limits {
  sessionTtlMs: number
  maxSessionsPerUser: number
  challengeTtlMs: number
  presenceTtlMs: number
  maxFriends: number
  maxOutgoingRequests: number
  maxIncomingRequests: number
  maxUploadsPerUser: number
  maxPendingUploadsPerUser: number
  maxSseStreamsPerUser: number
  maxSseStreamsTotal: number
  maxCosmeticUploadsPerUser: number
  maxPendingCosmeticUploadsPerUser: number
  maxPlayerStreamsPerUser: number
  maxPlayerStreamsTotal: number
  /** Höchstzahl beobachteter Spieler je `GET /v1/events/players`-Stream. */
  maxWatchedPerStream: number
  /** Geteilte Umhänge: höchstens so viele Inhaber je Umhang außer dem Ersteller (angenommen + offene Angebote). */
  maxCapeHolders: number
  /** Offene Umhang-Angebote, die ein Spieler gleichzeitig bekommen kann. */
  maxIncomingCapeOffers: number
  // ------------------------------------------------ Chat, Echtzeit, Meldungen
  /** Offene `GET /v1/events/me`-Streams je Konto (Launcher + Mod + Reserve). */
  maxUserStreamsPerUser: number
  /** Wiederaufnahme per Last-Event-ID: so viele Ereignisse je Konto … */
  replayBufferSize: number
  /** … höchstens so alt (danach `resync`). */
  replayWindowMs: number
  /** Zeichen je Nachricht (nach dem Säubern). */
  maxMessageLength: number
  maxAttachmentsPerMessage: number
  /** Mitglieder je Gruppe inkl. Besitzer. */
  maxGroupMembers: number
  maxGroupsOwned: number
  maxGroupsJoined: number
  /** Gespeicherte Chat-Bilder je Konto (Bytes, nach dem Neukodieren). */
  maxAttachmentBytesPerUser: number
  /** Hochgeladene, noch keiner Nachricht zugeordnete Bilder verfallen nach … */
  pendingAttachmentTtlMs: number
  /** Auto-Stumm: so viele verschiedene Melder … */
  autoMuteReporters: number
  /** … innerhalb dieses Fensters. */
  autoMuteWindowMs: number
  /** Offene Meldungen je Melder. */
  maxOpenReportsPerUser: number
  /** Beweise erledigter Meldungen werden nach … gelöscht. */
  reportEvidenceRetentionMs: number
  /** Erledigte Meldungen (ohne Inhalte) werden nach … ganz gelöscht. */
  reportRetentionMs: number
  // ------------------------------------------------ Welt-Hosting
  /** Raum schließt, wenn der Host so lange keinen Herzschlag schickt. */
  hostingRoomTtlMs: number
  /** Relay-Tokens gelten so lange zum Verbinden (≤ 2 min). */
  relayTokenTtlMs: number
  /** Offene Einladungen je Raum. */
  hostingMaxInvites: number
  /** Offene Beitrittsanfragen je Raum. */
  hostingMaxRequests: number
  /** Dauerhafte Sperren je Host. */
  hostingMaxBans: number
  /** Größe von `data` in Signal-Nachrichten (Zeichen). */
  hostingMaxSignalData: number
}

export const DEFAULT_LIMITS: Limits = {
  sessionTtlMs: 30 * 24 * 60 * 60 * 1000,
  maxSessionsPerUser: 10,
  challengeTtlMs: 60 * 1000,
  presenceTtlMs: 3 * 60 * 1000,
  maxFriends: 200,
  maxOutgoingRequests: 50,
  maxIncomingRequests: 100,
  maxUploadsPerUser: 10,
  maxPendingUploadsPerUser: 3,
  maxSseStreamsPerUser: 3,
  maxSseStreamsTotal: 2000,
  maxCosmeticUploadsPerUser: 10,
  maxPendingCosmeticUploadsPerUser: 3,
  maxPlayerStreamsPerUser: 3,
  maxPlayerStreamsTotal: 2000,
  maxWatchedPerStream: 200,
  maxCapeHolders: 20,
  maxIncomingCapeOffers: 50,
  maxUserStreamsPerUser: 5,
  replayBufferSize: 300,
  replayWindowMs: 10 * 60 * 1000,
  maxMessageLength: 2000,
  maxAttachmentsPerMessage: 10,
  maxGroupMembers: 25,
  maxGroupsOwned: 20,
  maxGroupsJoined: 100,
  maxAttachmentBytesPerUser: 250 * 1024 * 1024,
  pendingAttachmentTtlMs: 60 * 60 * 1000,
  autoMuteReporters: 3,
  autoMuteWindowMs: 24 * 60 * 60 * 1000,
  maxOpenReportsPerUser: 20,
  reportEvidenceRetentionMs: 90 * 24 * 60 * 60 * 1000,
  reportRetentionMs: 365 * 24 * 60 * 60 * 1000,
  hostingRoomTtlMs: 90 * 1000,
  relayTokenTtlMs: 120 * 1000,
  hostingMaxInvites: 50,
  hostingMaxRequests: 20,
  hostingMaxBans: 500,
  hostingMaxSignalData: 4096,
}

const bool = z
  .enum(['true', 'false', '1', '0', ''])
  .transform((v) => v === 'true' || v === '1')

const listOf = (item: (s: string) => string | null, label: string) =>
  z.string().transform((raw, ctx) => {
    const out = new Set<string>()
    for (const part of raw.split(',').map((s) => s.trim()).filter(Boolean)) {
      const v = item(part)
      if (!v) {
        ctx.addIssue({ code: 'custom', message: `invalid ${label}` })
        return z.NEVER
      }
      out.add(v)
    }
    return out
  })

const origin = (s: string): string | null => {
  try {
    const u = new URL(s)
    if (u.protocol !== 'https:' && u.hostname !== 'localhost' && u.hostname !== '127.0.0.1') return null
    if (u.origin !== s.replace(/\/$/, '')) return null
    return u.origin
  } catch {
    return null
  }
}

const envSchema = z.object({
  DATA_DIR: z.string().min(1).default('/data'),
  PUBLIC_BASE_URL: z
    .url({ protocol: /^https?$/ })
    .default('https://api.theredstonee.de')
    .transform((s) => s.replace(/\/+$/, '')),
  SITE_URL: z
    .url({ protocol: /^https?$/ })
    .default('https://trs-launcher.theredstonee.de')
    .transform((s) => s.replace(/\/+$/, '')),
  // Alte Namen, die nur noch API sind (Komma-Liste), z. B. api.theredstonee.de
  API_ONLY_HOSTS: z
    .string()
    .default('api.theredstonee.de')
    .transform((raw) => new Set(raw.split(',').map((h) => h.trim().toLowerCase()).filter(Boolean))),
  ADMIN_UUIDS: listOf(normalizeUuid, 'uuid').default(new Set<string>()),
  ADMIN_API_KEY: z
    .string()
    .default('')
    .refine((s) => s === '' || s.length >= 32, 'must be empty or at least 32 characters'),
  SECRET_KEY: z.string().min(32, 'must be at least 32 characters'),
  CORS_ORIGINS: listOf(origin, 'origin (https only, no wildcard)').default(new Set<string>()),
  TRUST_PROXY: z.enum(['cloudflare', 'none']).default('cloudflare'),
  MOJANG_SESSIONSERVER_URL: z
    .url({ protocol: /^https?$/ })
    .default('https://sessionserver.mojang.com')
    .transform((s) => s.replace(/\/+$/, '')),
  MOJANG_API_URL: z
    .url({ protocol: /^https?$/ })
    .default('https://api.mojang.com')
    .transform((s) => s.replace(/\/+$/, '')),
  // Nur für lokale Tests mit einem Mojang-Mock (http://…). Nie in Produktion setzen.
  ALLOW_INSECURE_MOJANG_URL: bool.default(false),
  LOG_REQUESTS: bool.default(false),
  // Chat-Verschlüsselung: "id:base64(32 Byte)", kommagetrennt, der erste ist aktiv.
  CHAT_KEYS: z.string().default(''),
  CHAT_STORAGE_MAX_MB: z.coerce.number().int().min(10).max(1_000_000).default(1024),
  SERVER_PING: bool.default(true),
  // Welt-Hosting: Relay (eigener Pterodactyl-Server). Ohne RELAY_SECRET + RELAY_HOST ist Hosting aus.
  RELAY_SECRET: z.string().default(''),
  RELAY_HOST: z
    .string()
    .trim()
    .default('')
    .refine((s) => s === '' || /^[A-Za-z0-9.-]{1,253}$/.test(s), 'must be a hostname or IPv4 address'),
  RELAY_TCP_PORT: z.coerce.number().int().min(1).max(65535).default(25503),
  RELAY_UDP_PORT: z.coerce.number().int().min(1).max(65535).default(25504),
  // STUN-Server (host:port, kommagetrennt). Leer = nur das Relay selbst (RELAY_HOST:RELAY_UDP_PORT).
  HOSTING_STUN: z.string().default(''),
})

const HOST_PORT = /^[A-Za-z0-9.-]{1,253}:(\d{1,5})$/

/** Relay-/STUN-Einstellungen lesen; ohne Geheimnis oder Host → `null` (Hosting aus). */
function parseHosting(e: z.output<typeof envSchema>): HostingConfig | null {
  const secrets = e.RELAY_SECRET.split(',').map((s) => s.trim()).filter(Boolean)
  if (secrets.length === 0 || e.RELAY_HOST === '') return null
  if (secrets.some((s) => s.length < 32)) throw new ConfigError('Invalid configuration: RELAY_SECRET (each secret must be at least 32 characters)')
  const stun = e.HOSTING_STUN.split(',').map((s) => s.trim()).filter(Boolean)
  for (const s of stun) {
    const m = HOST_PORT.exec(s)
    if (!m || Number(m[1]) < 1 || Number(m[1]) > 65535) throw new ConfigError('Invalid configuration: HOSTING_STUN (expected host:port, comma-separated)')
  }
  return {
    relayHost: e.RELAY_HOST,
    relayTcpPort: e.RELAY_TCP_PORT,
    relayUdpPort: e.RELAY_UDP_PORT,
    relaySecrets: secrets,
    stun: stun.length > 0 ? stun.slice(0, 8) : [`${e.RELAY_HOST}:${e.RELAY_UDP_PORT}`],
  }
}

/** `CHAT_KEYS` lesen; leer → ein Schlüssel aus SECRET_KEY (HKDF, Kennung `s1`). */
function parseChatKeys(raw: string, secret: string): Config['chatKeys'] {
  const parts = raw.split(',').map((s) => s.trim()).filter(Boolean)
  const derived = () => Buffer.from(hkdfSync('sha256', secret, 'trs-chat', 'chat-at-rest-v1', 32))
  if (parts.length === 0) return { keys: [{ id: 's1', key: derived() }], derived: true }
  const keys: { id: string, key: Buffer }[] = []
  for (const part of parts) {
    // `s1:derived` = der aus SECRET_KEY abgeleitete Schlüssel (zum Umstieg auf eigene Schlüssel).
    if (part === 's1:derived') {
      if (keys.some((k) => k.id === 's1')) throw new ConfigError('Invalid configuration: CHAT_KEYS (duplicate key id)')
      keys.push({ id: 's1', key: derived() })
      continue
    }
    const m = /^([A-Za-z0-9_-]{1,16}):([A-Za-z0-9+/=_-]{40,64})$/.exec(part)
    const key = m ? Buffer.from(m[2]!.replaceAll('-', '+').replaceAll('_', '/'), 'base64') : null
    if (!m || !key || key.length !== 32) throw new ConfigError('Invalid configuration: CHAT_KEYS (expected id:base64-of-32-bytes, comma-separated)')
    if (keys.some((k) => k.id === m[1])) throw new ConfigError('Invalid configuration: CHAT_KEYS (duplicate key id)')
    keys.push({ id: m[1]!, key })
  }
  return { keys, derived: false }
}

export class ConfigError extends Error {}

/** Liest die Konfiguration. Wirft `ConfigError` mit den Namen der fehlerhaften Variablen (nie deren Werte). */
export function loadConfig(env: Record<string, string | undefined>, limits: Partial<Limits> = {}): Config {
  const parsed = envSchema.safeParse(env)
  if (!parsed.success) {
    const keys = [...new Set(parsed.error.issues.map((i) => `${String(i.path[0])} (${i.message})`))]
    throw new ConfigError(`Invalid configuration: ${keys.join(', ')}`)
  }
  const e = parsed.data
  for (const key of ['MOJANG_SESSIONSERVER_URL', 'MOJANG_API_URL'] as const) {
    if (e[key].startsWith('http:') && !e.ALLOW_INSECURE_MOJANG_URL) {
      throw new ConfigError(`Invalid configuration: ${key} (must be https)`)
    }
  }
  return {
    dataDir: e.DATA_DIR,
    publicBaseUrl: e.PUBLIC_BASE_URL,
    siteUrl: e.SITE_URL,
    apiOnlyHosts: e.API_ONLY_HOSTS,
    adminUuids: e.ADMIN_UUIDS,
    adminApiKey: e.ADMIN_API_KEY === '' ? null : e.ADMIN_API_KEY,
    secretKey: e.SECRET_KEY,
    corsOrigins: e.CORS_ORIGINS,
    trustProxy: e.TRUST_PROXY,
    mojangSessionUrl: e.MOJANG_SESSIONSERVER_URL,
    mojangApiUrl: e.MOJANG_API_URL,
    logRequests: e.LOG_REQUESTS,
    chatKeys: parseChatKeys(e.CHAT_KEYS, e.SECRET_KEY),
    chatStorageMaxBytes: e.CHAT_STORAGE_MAX_MB * 1024 * 1024,
    serverPing: e.SERVER_PING,
    hosting: parseHosting(e),
    limits: { ...DEFAULT_LIMITS, ...limits },
  }
}
