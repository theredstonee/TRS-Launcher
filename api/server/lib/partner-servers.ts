import { createHash } from 'node:crypto'
import type { AppContext } from './context'
import { all, one, run } from './db'
import { hmacHex } from './ids'
import { defaultPingDeps, ServerStatusService, type PingDeps } from './serverping'

/**
 * Partner-Server im TRS Client (§44.3): öffentliche Liste (nur freigegeben, Partner aktiv, in den letzten 30 min
 * erreichbar, passende Version, höchstens 3, Reihenfolge je UTC-Tag), Status-Ping alle 5 min, Beitritte je Tag.
 */

const MIN = 60_000
const DAY = 86_400_000
export const SERVER_ONLINE_WINDOW_MS = 30 * MIN
export const SERVER_LIST_MAX = 3
export const SERVER_LIST_TTL_S = 600
export const SERVER_PING_INTERVAL_MS = 5 * MIN
export const SERVER_PING_TIMEOUT_MS = 5000
export const MOTD_MAX = 60
/** Minecraft-Version wie `1.8`, `1.21.11` oder `26.1`. */
export const MC_VERSION = /^\d{1,3}(?:\.\d{1,3}){1,3}$/

export interface PartnerServerRow {
  id: string
  partner_id: number
  name: string
  address: string
  min_version: string
  max_version: string
  motd: string
  hidden: number
  online: number
  latency_ms: number | null
  players_online: number | null
  players_max: number | null
  status_reason: string | null
  last_checked_at: number | null
  last_online_at: number | null
  approved_at: number
  updated_at: number
}

export interface PartnerServerItem {
  id: string
  partner: { slug: string, name: string }
  name: string
  address: string
  motd: string
  icon: string | null
  minVersion: string
  maxVersion: string
}

const dayOf = (t: number) => new Date(t).toISOString().slice(0, 10)

/** Vergleich zweier Versionen nach Zahlenteilen (fehlende Teile = 0). */
export function compareVersions(a: string, b: string): number {
  const pa = a.split('.').map(Number)
  const pb = b.split('.').map(Number)
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0)
    if (d !== 0) return d < 0 ? -1 : 1
  }
  return 0
}

/** Version aus der Anfrage (`1.21.11`, auch `1.21.11-pre1` → `1.21.11`); unbrauchbar → `null` (kein Filter). */
export function parseMcVersion(raw: unknown): string | null {
  if (typeof raw !== 'string' || raw.length > 32) return null
  const m = /^(\d{1,3}(?:\.\d{1,3}){1,3})/.exec(raw.trim())
  return m ? m[1]! : null
}

/** Eine Zeile, ohne §-Codes und Steuerzeichen, höchstens 60 Zeichen. */
export function cleanMotd(s: string): string {
  return s.replace(/§./g, '').replace(/[\p{Cc}\p{Cf}\p{Co}]/gu, ' ').replace(/\s+/g, ' ').trim().slice(0, MOTD_MAX)
}

export function logoPath(slug: string, sha: string | null): string | null {
  return sha ? `/v1/partners/${slug}/logo.png?v=${sha.slice(0, 12)}` : null
}

/** Reihenfolge des Tages: fest je UTC-Tag und Server, wechselt täglich. */
function dayRank(day: string, id: string): string {
  return createHash('sha256').update(`${day}:${id}`).digest('hex')
}

export function listPartnerServers(ctx: AppContext, mcRaw?: unknown): { servers: PartnerServerItem[], ttl: number } {
  const t = ctx.now()
  const mc = parseMcVersion(mcRaw)
  const rows = all<PartnerServerRow & { slug: string, partner_name: string, logo_sha: string | null }>(
    ctx.db,
    `SELECT s.*, p.slug, p.name AS partner_name, p.logo_sha FROM partner_servers s JOIN partners p ON p.id = s.partner_id
     WHERE p.state = 'active' AND s.hidden = 0 AND s.last_online_at IS NOT NULL AND s.last_online_at >= ?`,
    t - SERVER_ONLINE_WINDOW_MS,
  )
  const day = dayOf(t)
  const servers = rows
    .filter((r) => !mc || (compareVersions(r.min_version, mc) <= 0 && compareVersions(mc, r.max_version) <= 0))
    .sort((a, b) => (dayRank(day, a.id) < dayRank(day, b.id) ? -1 : 1))
    .slice(0, SERVER_LIST_MAX)
    .map((r) => ({
      id: r.id,
      partner: { slug: r.slug, name: r.partner_name },
      name: r.name,
      address: r.address,
      motd: r.motd,
      icon: logoPath(r.slug, r.logo_sha),
      minVersion: r.min_version,
      maxVersion: r.max_version,
    }))
  return { servers, ttl: SERVER_LIST_TTL_S }
}

/** Beitritt über die Liste: je Spieler, Server und UTC-Tag einmal (Spieler nur als HMAC gespeichert). */
export function recordServerJoin(ctx: AppContext, serverId: string, uuid: string | null): boolean {
  if (!uuid) return false
  if (!one(ctx.db, 'SELECT 1 AS x FROM partner_servers WHERE id = ?', serverId)) return false
  return run(
    ctx.db,
    'INSERT INTO partner_server_joins (server_id, day, player) VALUES (?, ?, ?) ON CONFLICT DO NOTHING',
    serverId, dayOf(ctx.now()), hmacHex(ctx.config.secretKey, `partner-join:${uuid}`).slice(0, 32),
  ) > 0
}

export function serverJoinStats(ctx: AppContext, serverId: string, days: number): { joins: number, distinct: number } {
  const from = dayOf(ctx.now() - (days - 1) * DAY)
  const r = one<{ joins: number, distinct_players: number }>(
    ctx.db,
    'SELECT COUNT(*) AS joins, COUNT(DISTINCT player) AS distinct_players FROM partner_server_joins WHERE server_id = ? AND day >= ?',
    serverId, from,
  )!
  return { joins: r.joins, distinct: r.distinct_players }
}

/**
 * Status-Ping aller freigegebenen Server aktiver Partner (Server List Ping, 5 s, SSRF-Schutz aus serverping.ts:
 * nur öffentliche Adressen, Ports 1–65535, SRV beachtet). Liefert die Zahl der erreichbaren Server.
 */
export async function pingPartnerServers(ctx: AppContext, deps?: PingDeps): Promise<number> {
  const rows = all<{ id: string, address: string }>(
    ctx.db,
    "SELECT s.id, s.address FROM partner_servers s JOIN partners p ON p.id = s.partner_id WHERE p.state = 'active' AND s.hidden = 0",
  )
  if (rows.length === 0) return 0
  const svc = new ServerStatusService(ctx.config.serverPing, ctx.now, deps ?? ctx.partnerPingDeps ?? defaultPingDeps, {
    timeoutMs: SERVER_PING_TIMEOUT_MS,
    minPort: 1,
    okTtlMs: 0,
    failTtlMs: 0,
    maxConcurrent: 8,
  })
  let online = 0
  for (let i = 0; i < rows.length; i += 8) {
    const part = rows.slice(i, i + 8)
    const results = await Promise.all(part.map((r) => svc.status(r.address)))
    const t = ctx.now()
    results.forEach((s, j) => {
      const id = part[j]!.id
      if (s.online) {
        online++
        run(
          ctx.db,
          'UPDATE partner_servers SET online = 1, latency_ms = ?, players_online = ?, players_max = ?, status_reason = NULL, last_checked_at = ?, last_online_at = ? WHERE id = ?',
          s.latencyMs, s.players?.online ?? null, s.players?.max ?? null, t, t, id,
        )
      } else {
        run(ctx.db, 'UPDATE partner_servers SET online = 0, latency_ms = NULL, status_reason = ?, last_checked_at = ? WHERE id = ?', s.reason, t, id)
      }
    })
  }
  return online
}
