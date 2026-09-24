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
  limits: Limits
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
})

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
    limits: { ...DEFAULT_LIMITS, ...limits },
  }
}
