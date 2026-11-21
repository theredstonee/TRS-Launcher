import { z } from 'zod'
import type { AppContext } from './context'
import { all, one, run } from './db'
import { badRequest, notFound } from './errors'
import { logoPath } from './partner-servers'
import { assertCan, type Staff } from './team'

// Partner-Statistik (§44.9): Tagesverlauf je Partner (Dashboard + Team) und Rangliste aller Partner für das Team.
// Wie die Creator-Statistik (§35.14): je Kennzahl EINE GROUP-BY-Abfrage über alle Partner, Rangliste kurz im Speicher.
// Nur Zahlen, nie Spielernamen.

const DAY = 86_400_000
/** „Neuer Spieler“: Konto höchstens so lange vor der ersten Einlösung eines Codes dieses Partners angelegt. */
export const PARTNER_NEW_PLAYER_MS = DAY
export const PARTNER_STATS_CACHE_MS = 60_000

const dayStr = (dayIndex: number) => new Date(dayIndex * DAY).toISOString().slice(0, 10)
const iso = (t: number) => new Date(t).toISOString()

function parse<S extends z.ZodType>(schema: S, raw: unknown): z.output<S> {
  const r = schema.safeParse(raw)
  if (!r.success) {
    throw badRequest('invalid_request', 'Request validation failed', {
      fields: r.error.issues.slice(0, 20).map((i) => ({ path: i.path.join('.') || '(body)', message: i.message })),
    })
  }
  return r.data
}

// ---------------------------------------------------------------- Tagesverlauf

export interface PartnerStatDay {
  day: string
  newSupporters: number
  left: number
  fixed: number
  onetime: number
  newPlayers: number
  serverJoins: number
  clicks: number
  downloads: number
  /** `null` = an diesem Tag noch nicht erfasst (vor Migration 52 bzw. vor dem ersten Schnappschuss). */
  activeSupporters: number | null
  playingSupporters: number | null
}

export interface PartnerPeriod {
  newSupporters: number
  left: number
  redemptions: number
  fixed: number
  onetime: number
  newPlayers: number
  serverJoins: number
  clicks: number
  downloads: number
}

/** Tageswerte von `first` (Tag-Index) an, `n` Tage, ältester zuerst. */
export function partnerSeries(ctx: AppContext, partnerId: number, first: number, n: number): PartnerStatDay[] {
  const days = Array.from({ length: n }, (_, i) => dayStr(first + i))
  const from = days[0]!
  const daily = new Map(all<{ day: string, fixed: number, onetime: number, new_supporters: number, left_count: number, active_supporters: number | null, playing_supporters: number | null }>(
    ctx.db,
    'SELECT day, fixed, onetime, new_supporters, left_count, active_supporters, playing_supporters FROM partner_daily WHERE partner_id = ? AND day >= ?',
    partnerId, from,
  ).map((r) => [r.day, r]))
  const perDay = (sql: string, ...p: (string | number)[]) => new Map(all<{ day: string, n: number }>(ctx.db, sql, ...p).map((r) => [r.day, r.n]))
  const newPlayers = perDay(
    `SELECT strftime('%Y-%m-%d', f.first / 1000, 'unixepoch') AS day, COUNT(*) AS n
     FROM (SELECT uuid, MIN(at) AS first FROM partner_redemptions WHERE partner_id = ? GROUP BY uuid) f
     JOIN users u ON u.uuid = f.uuid WHERE f.first - u.created_at <= ? AND f.first >= ? GROUP BY day`,
    partnerId, PARTNER_NEW_PLAYER_MS, first * DAY,
  )
  const joins = perDay(
    `SELECT j.day, COUNT(*) AS n FROM partner_server_joins j JOIN partner_servers s ON s.id = j.server_id
     WHERE s.partner_id = ? AND j.day >= ? GROUP BY j.day`,
    partnerId, from,
  )
  const links = new Map(all<{ day: string, clicks: number, downloads: number }>(
    ctx.db,
    'SELECT day, SUM(clicks) AS clicks, SUM(downloads) AS downloads FROM partner_link_stats WHERE partner_id = ? AND day >= ? GROUP BY day',
    partnerId, from,
  ).map((r) => [r.day, r]))
  const since = one<{ day: string | null }>(
    ctx.db, 'SELECT MIN(day) AS day FROM partner_daily WHERE partner_id = ? AND active_supporters IS NOT NULL', partnerId,
  )!.day
  return days.map((day) => {
    const d = daily.get(day)
    const l = links.get(day)
    const tracked = since !== null && day >= since
    return {
      day,
      newSupporters: d?.new_supporters ?? 0,
      left: d?.left_count ?? 0,
      fixed: d?.fixed ?? 0,
      onetime: d?.onetime ?? 0,
      newPlayers: newPlayers.get(day) ?? 0,
      serverJoins: joins.get(day) ?? 0,
      clicks: l?.clicks ?? 0,
      downloads: l?.downloads ?? 0,
      activeSupporters: tracked ? (d?.active_supporters ?? 0) : null,
      playingSupporters: tracked ? (d?.playing_supporters ?? 0) : null,
    }
  })
}

export function sumPeriod(days: readonly PartnerStatDay[]): PartnerPeriod {
  const s = (f: (d: PartnerStatDay) => number) => days.reduce((a, d) => a + f(d), 0)
  const fixed = s((d) => d.fixed)
  const onetime = s((d) => d.onetime)
  return {
    newSupporters: s((d) => d.newSupporters),
    left: s((d) => d.left),
    redemptions: fixed + onetime,
    fixed,
    onetime,
    newPlayers: s((d) => d.newPlayers),
    serverJoins: s((d) => d.serverJoins),
    clicks: s((d) => d.clicks),
    downloads: s((d) => d.downloads),
  }
}

/** Dashboard: letzte 30 Tage (bis heute) + Summen dieser und der 30 Tage davor. */
export function partnerTrend(ctx: AppContext, partnerId: number): { daily: PartnerStatDay[], current30d: PartnerPeriod, previous30d: PartnerPeriod } {
  const today = Math.floor(ctx.now() / DAY)
  const series = partnerSeries(ctx, partnerId, today - 59, 60)
  const daily = series.slice(30)
  return { daily, current30d: sumPeriod(daily), previous30d: sumPeriod(series.slice(0, 30)) }
}

// ---------------------------------------------------------------- Rangliste (Team)

export type PartnerStatState = 'active' | 'paused'
export type PartnerStatCategory = 'hosting' | 'network' | 'other'

export interface PartnerStatRow {
  slug: string
  name: string
  category: PartnerStatCategory
  state: PartnerStatState
  logo: string | null
  code: string | null
  since: string
  serverAllowed: boolean
  /** total = aktuelle Unterstützer; today/d7/d30 = neue Unterstützungen. */
  supporters: { total: number, today: number, d7: number, d30: number }
  redemptions: { total: number, fixed: number, onetime: number, d30: number }
  newPlayers: { total: number, d30: number }
  left30d: number
  serverJoins: { d7: number, d30: number }
  links: { clicks30d: number, downloads30d: number }
  /** Aktuelle Unterstützer mit Präsenz (online) bzw. Spiel heute und online in den letzten 7 Tagen. */
  activity: { onlineToday: number, playedToday: number, online7d: number }
  /** Aktuelle Unterstützer, die einen zugeordneten Umhang tragen / eine zugeordnete Kosmetik angelegt haben. */
  worn: { cape: number, cosmetic: number }
  quota: { total: number, used: number }
}

export interface PartnerStatDefinitions {
  newPlayerWindowHours: number
  /** Erster Tag mit erfasster Unterstützer-Aktivität (Migration 52), `null` = noch nie. */
  activitySince: string | null
}

export interface PartnerStatTotals {
  partners: number
  supporters: number
  supporters30d: number
  redemptions30d: number
  newPlayers30d: number
  serverJoins30d: number
  downloads30d: number
}

interface Snapshot {
  at: number
  rows: PartnerStatRow[]
  activitySince: string | null
}

const cache = new WeakMap<AppContext, Snapshot>()

export function clearPartnerStatsCache(ctx: AppContext): void {
  cache.delete(ctx)
}

function byPartner<T extends { pid: number }>(rows: T[]): Map<number, T> {
  return new Map(rows.map((r) => [r.pid, r]))
}

function computeRows(ctx: AppContext): Snapshot {
  const t = ctx.now()
  const today = Math.floor(t / DAY)
  const d7 = today - 6
  const d30 = today - 29
  const base = all<{ id: number, slug: string, name: string, category: PartnerStatCategory, state: PartnerStatState, logo_sha: string | null, created_at: number, server_allowed: number, quota_total: number, quota_used: number, code: string | null }>(
    ctx.db,
    `SELECT p.id, p.slug, p.name, p.category, p.state, p.logo_sha, p.created_at, p.server_allowed, p.quota_total, p.quota_used,
       (SELECT k.code FROM partner_codes k WHERE k.partner_id = p.id AND k.kind = 'fixed' AND k.main = 1) AS code
     FROM partners p`,
  )
  const supporters = byPartner(all<{ pid: number, n: number }>(
    ctx.db, 'SELECT partner_id AS pid, COUNT(*) AS n FROM partner_support WHERE partner_id IS NOT NULL GROUP BY partner_id',
  ))
  const daily = byPartner(all<{ pid: number, today: number, d7: number, d30: number, red30: number, left30: number }>(
    ctx.db,
    `SELECT partner_id AS pid,
       SUM(CASE WHEN day = ? THEN new_supporters ELSE 0 END) AS today,
       SUM(CASE WHEN day >= ? THEN new_supporters ELSE 0 END) AS d7,
       SUM(new_supporters) AS d30, SUM(fixed + onetime) AS red30, SUM(left_count) AS left30
     FROM partner_daily WHERE day >= ? GROUP BY partner_id`,
    dayStr(today), dayStr(d7), dayStr(d30),
  ))
  const redemptions = byPartner(all<{ pid: number, fixed: number, onetime: number }>(
    ctx.db,
    "SELECT partner_id AS pid, SUM(kind = 'fixed') AS fixed, SUM(kind = 'onetime') AS onetime FROM partner_redemptions GROUP BY partner_id",
  ))
  const newPlayers = byPartner(all<{ pid: number, total: number, d30: number }>(
    ctx.db,
    `SELECT f.pid, COUNT(*) AS total, SUM(f.first >= ?) AS d30
     FROM (SELECT partner_id AS pid, uuid, MIN(at) AS first FROM partner_redemptions GROUP BY partner_id, uuid) f
     JOIN users u ON u.uuid = f.uuid WHERE f.first - u.created_at <= ? GROUP BY f.pid`,
    d30 * DAY, PARTNER_NEW_PLAYER_MS,
  ))
  const joins = byPartner(all<{ pid: number, d7: number, d30: number }>(
    ctx.db,
    `SELECT s.partner_id AS pid, SUM(j.day >= ?) AS d7, COUNT(*) AS d30
     FROM partner_server_joins j JOIN partner_servers s ON s.id = j.server_id WHERE j.day >= ? GROUP BY s.partner_id`,
    dayStr(d7), dayStr(d30),
  ))
  const links = byPartner(all<{ pid: number, clicks: number, downloads: number }>(
    ctx.db,
    'SELECT partner_id AS pid, SUM(clicks) AS clicks, SUM(downloads) AS downloads FROM partner_link_stats WHERE day >= ? GROUP BY partner_id',
    dayStr(d30),
  ))
  const activity = byPartner(all<{ pid: number, online: number, played: number, o7: number }>(
    ctx.db,
    `SELECT s.partner_id AS pid, SUM(a.last_day = ?) AS online, SUM(a.last_day = ? AND COALESCE(a.last_played_day, -1) = ?) AS played,
       COUNT(*) AS o7
     FROM partner_support s JOIN achievement_playtime a ON a.uuid = s.uuid
     WHERE s.partner_id IS NOT NULL AND a.last_day >= ? GROUP BY s.partner_id`,
    today, today, today, d7,
  ))
  const capeWorn = byPartner(all<{ pid: number, n: number }>(
    ctx.db,
    `SELECT s.partner_id AS pid, COUNT(*) AS n FROM partner_support s JOIN users u ON u.uuid = s.uuid
     WHERE s.partner_id IS NOT NULL AND EXISTS (
       SELECT 1 FROM partner_items i WHERE i.partner_id = s.partner_id AND i.kind = 'cape' AND i.item_id = u.active_cape_id)
     GROUP BY s.partner_id`,
  ))
  const cosmeticWorn = byPartner(all<{ pid: number, n: number }>(
    ctx.db,
    `SELECT s.partner_id AS pid, COUNT(*) AS n FROM partner_support s
     WHERE s.partner_id IS NOT NULL AND EXISTS (
       SELECT 1 FROM equipped_cosmetics e JOIN partner_items i ON i.item_id = e.cosmetic_id AND i.kind = 'cosmetic'
       WHERE e.uuid = s.uuid AND i.partner_id = s.partner_id)
     GROUP BY s.partner_id`,
  ))
  const since = one<{ day: string | null }>(ctx.db, 'SELECT MIN(day) AS day FROM partner_daily WHERE active_supporters IS NOT NULL')!.day
  const rows = base.map((p): PartnerStatRow => {
    const d = daily.get(p.id)
    const r = redemptions.get(p.id)
    const np = newPlayers.get(p.id)
    const j = joins.get(p.id)
    const l = links.get(p.id)
    const a = activity.get(p.id)
    return {
      slug: p.slug,
      name: p.name,
      category: p.category,
      state: p.state,
      logo: logoPath(p.slug, p.logo_sha),
      code: p.code,
      since: iso(p.created_at),
      serverAllowed: p.server_allowed === 1,
      supporters: { total: supporters.get(p.id)?.n ?? 0, today: d?.today ?? 0, d7: d?.d7 ?? 0, d30: d?.d30 ?? 0 },
      redemptions: { total: (r?.fixed ?? 0) + (r?.onetime ?? 0), fixed: r?.fixed ?? 0, onetime: r?.onetime ?? 0, d30: d?.red30 ?? 0 },
      newPlayers: { total: np?.total ?? 0, d30: np?.d30 ?? 0 },
      left30d: d?.left30 ?? 0,
      serverJoins: { d7: j?.d7 ?? 0, d30: j?.d30 ?? 0 },
      links: { clicks30d: l?.clicks ?? 0, downloads30d: l?.downloads ?? 0 },
      activity: { onlineToday: a?.online ?? 0, playedToday: a?.played ?? 0, online7d: a?.o7 ?? 0 },
      worn: { cape: capeWorn.get(p.id)?.n ?? 0, cosmetic: cosmeticWorn.get(p.id)?.n ?? 0 },
      quota: { total: p.quota_total, used: p.quota_used },
    }
  })
  return { at: t, rows, activitySince: since }
}

function snapshot(ctx: AppContext): Snapshot {
  const c = cache.get(ctx)
  if (c && ctx.now() - c.at < PARTNER_STATS_CACHE_MS && ctx.now() >= c.at) return c
  const s = computeRows(ctx)
  cache.set(ctx, s)
  return s
}

export const PARTNER_STAT_SORTS = [
  'supporters', 'supporters7d', 'supporters30d', 'redemptions', 'redemptions30d', 'newPlayers', 'newPlayers30d',
  'joins7d', 'joins30d', 'clicks30d', 'downloads30d', 'onlineToday', 'online7d', 'left30d', 'capeWorn', 'name', 'since',
] as const
export type PartnerStatSort = (typeof PARTNER_STAT_SORTS)[number]

const SORT_KEY: Record<PartnerStatSort, (r: PartnerStatRow) => number | string> = {
  supporters: (r) => r.supporters.total,
  supporters7d: (r) => r.supporters.d7,
  supporters30d: (r) => r.supporters.d30,
  redemptions: (r) => r.redemptions.total,
  redemptions30d: (r) => r.redemptions.d30,
  newPlayers: (r) => r.newPlayers.total,
  newPlayers30d: (r) => r.newPlayers.d30,
  joins7d: (r) => r.serverJoins.d7,
  joins30d: (r) => r.serverJoins.d30,
  clicks30d: (r) => r.links.clicks30d,
  downloads30d: (r) => r.links.downloads30d,
  onlineToday: (r) => r.activity.onlineToday,
  online7d: (r) => r.activity.online7d,
  left30d: (r) => r.left30d,
  capeWorn: (r) => r.worn.cape,
  name: (r) => r.name.toLowerCase(),
  since: (r) => r.since,
}

export const partnerStatsQuery = z.strictObject({
  q: z.string().trim().max(40).optional(),
  state: z.enum(['active', 'paused', 'all']).default('all'),
  category: z.enum(['hosting', 'network', 'other']).optional(),
  sort: z.enum(PARTNER_STAT_SORTS).default('supporters'),
  order: z.enum(['asc', 'desc']).default('desc'),
})

export interface PartnerStatsList {
  generatedAt: string
  definitions: PartnerStatDefinitions
  totals: PartnerStatTotals
  partners: PartnerStatRow[]
}

function definitions(activitySince: string | null): PartnerStatDefinitions {
  return { newPlayerWindowHours: Math.round(PARTNER_NEW_PLAYER_MS / 3_600_000), activitySince }
}

/** `GET /v1/admin/partners/stats` – Rangliste aller Partner (Filter + Sortierung auf der zwischengespeicherten Kopie). */
export function adminPartnerStats(ctx: AppContext, staff: Staff, rawQuery: unknown): PartnerStatsList {
  assertCan(staff, 'partners.manage')
  const q = parse(partnerStatsQuery, rawQuery)
  const s = snapshot(ctx)
  let rows = s.rows
  if (q.state !== 'all') rows = rows.filter((r) => r.state === q.state)
  if (q.category) rows = rows.filter((r) => r.category === q.category)
  if (q.q) {
    const needle = q.q.toLowerCase()
    rows = rows.filter((r) => r.name.toLowerCase().includes(needle) || r.slug.includes(needle) || (r.code ?? '').toLowerCase().includes(needle))
  }
  const key = SORT_KEY[q.sort]
  const dir = q.order === 'asc' ? 1 : -1
  rows = [...rows].sort((a, b) => {
    const x = key(a)
    const y = key(b)
    if (x !== y) return (x < y ? -1 : 1) * dir
    if (a.supporters.total !== b.supporters.total) return b.supporters.total - a.supporters.total
    return a.name.localeCompare(b.name)
  })
  const sum = (f: (r: PartnerStatRow) => number) => rows.reduce((a, r) => a + f(r), 0)
  return {
    generatedAt: iso(s.at),
    definitions: definitions(s.activitySince),
    totals: {
      partners: rows.length,
      supporters: sum((r) => r.supporters.total),
      supporters30d: sum((r) => r.supporters.d30),
      redemptions30d: sum((r) => r.redemptions.d30),
      newPlayers30d: sum((r) => r.newPlayers.d30),
      serverJoins30d: sum((r) => r.serverJoins.d30),
      downloads30d: sum((r) => r.links.downloads30d),
    },
    partners: rows,
  }
}

export const partnerStatsDetailQuery = z.strictObject({
  days: z.coerce.number().int().min(7).max(365).default(30),
})

export interface PartnerStatsDetail {
  generatedAt: string
  definitions: PartnerStatDefinitions
  partner: PartnerStatRow
  series: PartnerStatDay[]
  /** Summen des gewählten Zeitraums und des gleich langen Zeitraums davor. */
  period: PartnerPeriod
  previous: PartnerPeriod
}

/** `GET /v1/admin/partners/{slug}/stats?days=` – Kennzahlen + Tagesverlauf (ältester Tag zuerst, letzter = heute). */
export function adminPartnerStatsDetail(ctx: AppContext, staff: Staff, slug: string, rawQuery: unknown): PartnerStatsDetail {
  assertCan(staff, 'partners.manage')
  const q = parse(partnerStatsDetailQuery, rawQuery)
  const p = one<{ id: number }>(ctx.db, 'SELECT id FROM partners WHERE slug = ?', slug.toLowerCase())
  if (!p) throw notFound('partner_not_found', 'Partner not found')
  let s = snapshot(ctx)
  let row = s.rows.find((r) => r.slug === slug.toLowerCase())
  if (!row) {
    clearPartnerStatsCache(ctx)
    s = snapshot(ctx)
    row = s.rows.find((r) => r.slug === slug.toLowerCase())
    if (!row) throw notFound('partner_not_found', 'Partner not found')
  }
  const today = Math.floor(ctx.now() / DAY)
  const both = partnerSeries(ctx, p.id, today - (2 * q.days - 1), 2 * q.days)
  const series = both.slice(q.days)
  return {
    generatedAt: iso(s.at),
    definitions: definitions(s.activitySince),
    partner: row,
    series,
    period: sumPeriod(series),
    previous: sumPeriod(both.slice(0, q.days)),
  }
}

// ---------------------------------------------------------------- Tages-Schnappschuss

/**
 * Alle 10 min (00.context.ts): je aktivem Partner die aktuellen Unterstützer mit Präsenz bzw. Spiel HEUTE in
 * `partner_daily` (Maximum über den Tag, wie snapshotCreatorActivity). Liefert die Zahl der geschriebenen Zeilen.
 */
export function snapshotPartnerActivity(ctx: AppContext): number {
  const today = Math.floor(ctx.now() / DAY)
  return run(
    ctx.db,
    `INSERT INTO partner_daily (partner_id, day, active_supporters, playing_supporters)
     SELECT p.id, ?, COALESCE(a.online, 0), COALESCE(a.playing, 0) FROM partners p
     LEFT JOIN (SELECT s.partner_id AS pid, COUNT(*) AS online, SUM(COALESCE(t.last_played_day, -1) = ?) AS playing
                FROM achievement_playtime t JOIN partner_support s ON s.uuid = t.uuid
                WHERE t.last_day = ? AND s.partner_id IS NOT NULL GROUP BY s.partner_id) a ON a.pid = p.id
     WHERE p.state = 'active'
     ON CONFLICT(partner_id, day) DO UPDATE SET
       active_supporters = MAX(COALESCE(active_supporters, 0), excluded.active_supporters),
       playing_supporters = MAX(COALESCE(playing_supporters, 0), excluded.playing_supporters)`,
    dayStr(today), today, today,
  )
}
