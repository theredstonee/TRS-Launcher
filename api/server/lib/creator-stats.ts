import { z } from 'zod'
import type { AppContext } from './context'
import { monthStart, petLimits } from './creator-pets'
import { NEW_PLAYER_WINDOW_MS, parse, TIERS, type CreatorState, type CreatorTier } from './creators'
import { all, one, run } from './db'
import { notFound } from './errors'
import { normalizeUuid } from './ids'

// Creator-Statistik für das Team (§35.14): Rangliste aller Creator + Verlauf je Creator. Nur Zahlen, nie Namen von Fans.
// Alles mit wenigen GROUP-BY-Abfragen über alle Creator (kein N+1); die Rangliste liegt kurz im Speicher.

const DAY = 86_400_000
/** Wie lange die Rangliste wiederverwendet wird (Filter/Sortierung laufen auf der Kopie). */
export const STATS_CACHE_MS = 60_000

const dayStr = (dayIndex: number) => new Date(dayIndex * DAY).toISOString().slice(0, 10)
const iso = (t: number) => new Date(t).toISOString()

export interface CreatorStatRow {
  uuid: string
  name: string
  tier: CreatorTier
  state: CreatorState
  since: string
  mainCode: string | null
  /** total = aktuelle Fans; today/d7/d30 = neue Unterstützungen (Tageswerte). */
  fans: { total: number, today: number, d7: number, d30: number }
  newPlayers: { total: number, d7: number, d30: number }
  pets: {
    pet: { cosmeticId: string, name: string } | null
    codes: number
    activeCodes: number
    redemptions: number
    month: { month: string, used: number, limit: number, redemptions: number }
  }
  /** Aktuelle Fans mit Präsenz (online) bzw. Spiel (played) in den letzten 7/30 UTC-Tagen inkl. heute. */
  activity: { online7d: number, online30d: number, played7d: number, played30d: number }
}

export interface StatDefinitions {
  newPlayerWindowHours: number
  /** Erster Tag mit erfasster Fan-Aktivität (Migration 36), `null` = noch nie erfasst. */
  activitySince: string | null
}

export interface StatsTotals {
  creators: number
  fans: number
  fans7d: number
  fans30d: number
  newPlayers: number
  newPlayers30d: number
  petRedemptions: number
  online7d: number
  online30d: number
}

interface Snapshot {
  at: number
  rows: CreatorStatRow[]
  activitySince: string | null
}

const cache = new WeakMap<AppContext, Snapshot>()

/** Nach Änderungen in Tests oder nach Admin-Aktionen sofort neu rechnen. */
export function clearCreatorStatsCache(ctx: AppContext): void {
  cache.delete(ctx)
}

function definitions(activitySince: string | null): StatDefinitions {
  return { newPlayerWindowHours: Math.round(NEW_PLAYER_WINDOW_MS / 3_600_000), activitySince }
}

function byCreator<T extends { creator: string }>(rows: T[]): Map<string, T> {
  return new Map(rows.map((r) => [r.creator, r]))
}

/** Alle Kennzahlen aller Creator – je Kennzahl EINE Abfrage über alle Creator. */
function computeRows(ctx: AppContext): Snapshot {
  const t = ctx.now()
  const today = Math.floor(t / DAY)
  const d7 = today - 6
  const d30 = today - 29
  const month = monthStart(t)
  const limits = petLimits(ctx)

  const base = all<{ uuid: string, tier: CreatorTier, state: CreatorState, created_at: number, name: string | null, main_code: string | null }>(
    ctx.db,
    `SELECT c.uuid, c.tier, c.state, c.created_at, u.name,
       (SELECT k.code FROM creator_codes k WHERE k.creator_uuid = c.uuid AND k.kind = 'main') AS main_code
     FROM creators c LEFT JOIN users u ON u.uuid = c.uuid`,
  )
  const fans = byCreator(all<{ creator: string, n: number }>(
    ctx.db, 'SELECT creator_uuid AS creator, COUNT(*) AS n FROM creator_supports WHERE creator_uuid IS NOT NULL GROUP BY creator_uuid',
  ))
  const newFans = byCreator(all<{ creator: string, today: number, d7: number, d30: number }>(
    ctx.db,
    `SELECT creator_uuid AS creator,
       SUM(CASE WHEN day = ? THEN new_fans ELSE 0 END) AS today,
       SUM(CASE WHEN day >= ? THEN new_fans ELSE 0 END) AS d7,
       SUM(new_fans) AS d30
     FROM creator_daily WHERE day >= ? GROUP BY creator_uuid`,
    dayStr(today), dayStr(d7), dayStr(d30),
  ))
  // Neue Spieler: erste Einlösung eines Codes des Creators je Fan; Konto höchstens NEW_PLAYER_WINDOW_MS davor angelegt.
  const newPlayers = byCreator(all<{ creator: string, total: number, d7: number, d30: number }>(
    ctx.db,
    `SELECT f.creator, COUNT(*) AS total, SUM(f.first >= ?) AS d7, SUM(f.first >= ?) AS d30
     FROM (SELECT k.creator_uuid AS creator, r.fan_uuid, MIN(r.redeemed_at) AS first
           FROM creator_code_redemptions r JOIN creator_codes k ON k.id = r.code_id GROUP BY k.creator_uuid, r.fan_uuid) f
     JOIN users u ON u.uuid = f.fan_uuid
     WHERE f.first - u.created_at <= ? GROUP BY f.creator`,
    d7 * DAY, d30 * DAY, NEW_PLAYER_WINDOW_MS,
  ))
  // Haustier-Codes = normale Codes mit Herkunft Creator (§35.13); `uses` zählt die Einlösungen.
  const petCodes = byCreator(all<{ creator: string, codes: number, active: number, redemptions: number, used: number }>(
    ctx.db,
    `SELECT creator_uuid AS creator, COUNT(*) AS codes,
       SUM(revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?) AND uses < max_uses) AS active,
       SUM(uses) AS redemptions,
       SUM(CASE WHEN created_at >= ? THEN (CASE WHEN revoked_at IS NULL THEN max_uses ELSE uses END) ELSE 0 END) AS used
     FROM codes WHERE creator_uuid IS NOT NULL GROUP BY creator_uuid`,
    t, month,
  ))
  const petMonth = byCreator(all<{ creator: string, n: number }>(
    ctx.db,
    `SELECT k.creator_uuid AS creator, COUNT(*) AS n FROM codes k JOIN code_redemptions r ON r.code_id = k.id
     WHERE k.creator_uuid IS NOT NULL AND r.redeemed_at >= ? GROUP BY k.creator_uuid`,
    month,
  ))
  const pets = byCreator(all<{ creator: string, cosmetic_id: string, name: string | null }>(
    ctx.db,
    'SELECT p.creator_uuid AS creator, p.cosmetic_id, x.name FROM creator_pets p LEFT JOIN cosmetics x ON x.id = p.cosmetic_id',
  ))
  const activity = byCreator(all<{ creator: string, o7: number, o30: number, p7: number, p30: number }>(
    ctx.db,
    `SELECT s.creator_uuid AS creator,
       SUM(p.last_day >= ?) AS o7, SUM(p.last_day >= ?) AS o30,
       SUM(COALESCE(p.last_played_day, -1) >= ?) AS p7, SUM(COALESCE(p.last_played_day, -1) >= ?) AS p30
     FROM creator_supports s JOIN achievement_playtime p ON p.uuid = s.fan_uuid
     WHERE s.creator_uuid IS NOT NULL AND p.last_day >= ? GROUP BY s.creator_uuid`,
    d7, d30, d7, d30, d30,
  ))
  const since = one<{ day: string | null }>(ctx.db, 'SELECT MIN(day) AS day FROM creator_daily WHERE active_fans IS NOT NULL')!.day

  const monthLabel = new Date(month).toISOString().slice(0, 7)
  const rows: CreatorStatRow[] = base.map((c) => {
    const nf = newFans.get(c.uuid)
    const np = newPlayers.get(c.uuid)
    const pc = petCodes.get(c.uuid)
    const pet = pets.get(c.uuid)
    const act = activity.get(c.uuid)
    return {
      uuid: c.uuid,
      name: c.name ?? '',
      tier: c.tier,
      state: c.state,
      since: iso(c.created_at),
      mainCode: c.main_code,
      fans: { total: fans.get(c.uuid)?.n ?? 0, today: nf?.today ?? 0, d7: nf?.d7 ?? 0, d30: nf?.d30 ?? 0 },
      newPlayers: { total: np?.total ?? 0, d7: np?.d7 ?? 0, d30: np?.d30 ?? 0 },
      pets: {
        pet: pet ? { cosmeticId: pet.cosmetic_id, name: pet.name ?? pet.cosmetic_id } : null,
        codes: pc?.codes ?? 0,
        activeCodes: pc?.active ?? 0,
        redemptions: pc?.redemptions ?? 0,
        month: { month: monthLabel, used: pc?.used ?? 0, limit: limits[c.tier], redemptions: petMonth.get(c.uuid)?.n ?? 0 },
      },
      activity: { online7d: act?.o7 ?? 0, online30d: act?.o30 ?? 0, played7d: act?.p7 ?? 0, played30d: act?.p30 ?? 0 },
    }
  })
  return { at: t, rows, activitySince: since }
}

function snapshot(ctx: AppContext): Snapshot {
  const c = cache.get(ctx)
  if (c && ctx.now() - c.at < STATS_CACHE_MS && ctx.now() >= c.at) return c
  const s = computeRows(ctx)
  cache.set(ctx, s)
  return s
}

// ---------------------------------------------------------------- Rangliste

export const STAT_SORTS = [
  'fans', 'fansToday', 'fans7d', 'fans30d', 'newPlayers', 'newPlayers7d', 'newPlayers30d', 'petCodes', 'petRedemptions',
  'online7d', 'online30d', 'played7d', 'played30d', 'name', 'since',
] as const
export type StatSort = (typeof STAT_SORTS)[number]

const SORT_KEY: Record<StatSort, (r: CreatorStatRow) => number | string> = {
  fans: (r) => r.fans.total,
  fansToday: (r) => r.fans.today,
  fans7d: (r) => r.fans.d7,
  fans30d: (r) => r.fans.d30,
  newPlayers: (r) => r.newPlayers.total,
  newPlayers7d: (r) => r.newPlayers.d7,
  newPlayers30d: (r) => r.newPlayers.d30,
  petCodes: (r) => r.pets.codes,
  petRedemptions: (r) => r.pets.redemptions,
  online7d: (r) => r.activity.online7d,
  online30d: (r) => r.activity.online30d,
  played7d: (r) => r.activity.played7d,
  played30d: (r) => r.activity.played30d,
  name: (r) => r.name.toLowerCase(),
  since: (r) => r.since,
}

export const statsListQuery = z.strictObject({
  q: z.string().trim().max(32).optional(),
  tier: z.enum(TIERS).optional(),
  state: z.enum(['active', 'revoked', 'banned', 'all']).default('all'),
  sort: z.enum(STAT_SORTS).default('fans'),
  order: z.enum(['asc', 'desc']).default('desc'),
  limit: z.coerce.number().int().min(1).max(500).default(500),
})

export interface CreatorStatsList {
  generatedAt: string
  definitions: StatDefinitions
  totals: StatsTotals
  creators: CreatorStatRow[]
}

function totalsOf(rows: CreatorStatRow[]): StatsTotals {
  const sum = (f: (r: CreatorStatRow) => number) => rows.reduce((s, r) => s + f(r), 0)
  return {
    creators: rows.length,
    fans: sum((r) => r.fans.total),
    fans7d: sum((r) => r.fans.d7),
    fans30d: sum((r) => r.fans.d30),
    newPlayers: sum((r) => r.newPlayers.total),
    newPlayers30d: sum((r) => r.newPlayers.d30),
    petRedemptions: sum((r) => r.pets.redemptions),
    online7d: sum((r) => r.activity.online7d),
    online30d: sum((r) => r.activity.online30d),
  }
}

/** `GET /v1/admin/creators/stats` – Rangliste (Filter + Sortierung auf der zwischengespeicherten Kopie). */
export function adminCreatorStats(ctx: AppContext, rawQuery: unknown): CreatorStatsList {
  const q = parse(statsListQuery, rawQuery)
  const s = snapshot(ctx)
  let rows = s.rows
  if (q.state !== 'all') rows = rows.filter((r) => r.state === q.state)
  if (q.tier) rows = rows.filter((r) => r.tier === q.tier)
  if (q.q) {
    const needle = q.q.toLowerCase()
    const uuid = normalizeUuid(q.q)
    const codeHits = new Set(all<{ creator_uuid: string }>(
      ctx.db, 'SELECT DISTINCT creator_uuid FROM creator_codes WHERE instr(code, upper(?)) > 0', q.q,
    ).map((r) => r.creator_uuid))
    rows = rows.filter((r) => r.name.toLowerCase().includes(needle) || r.uuid === uuid || codeHits.has(r.uuid))
  }
  const key = SORT_KEY[q.sort]
  const dir = q.order === 'asc' ? 1 : -1
  // Gleichstand: mehr Fans zuerst, dann Name – damit die Reihenfolge stabil bleibt.
  rows = [...rows].sort((a, b) => {
    const x = key(a)
    const y = key(b)
    if (x !== y) return (x < y ? -1 : 1) * dir
    if (a.fans.total !== b.fans.total) return b.fans.total - a.fans.total
    return a.name.localeCompare(b.name)
  })
  return {
    generatedAt: iso(s.at),
    definitions: definitions(s.activitySince),
    totals: totalsOf(rows),
    creators: rows.slice(0, q.limit),
  }
}

// ---------------------------------------------------------------- Detail mit Verlauf

export const statsDetailQuery = z.strictObject({
  days: z.coerce.number().int().min(7).max(365).default(90),
})

export interface StatDay {
  day: string
  newFans: number
  newPlayers: number
  redemptions: number
  petRedemptions: number
  /** `null` = an diesem Tag noch nicht erfasst (vor Migration 36 bzw. vor dem ersten Schnappschuss des Creators). */
  activeFans: number | null
  playingFans: number | null
}

export interface CreatorStatsDetail {
  generatedAt: string
  definitions: StatDefinitions
  creator: CreatorStatRow
  fanCape: { holders: number, fanHolders: number }
  series: StatDay[]
}

/** `GET /v1/admin/creators/{uuid}/stats?days=` – Kennzahlen + Tagesverlauf (ältester Tag zuerst, letzter = heute). */
export function adminCreatorStatsDetail(ctx: AppContext, creator: string, rawQuery: unknown): CreatorStatsDetail {
  const q = parse(statsDetailQuery, rawQuery)
  const s = snapshot(ctx)
  let row = s.rows.find((r) => r.uuid === creator)
  if (!row) {
    // Gerade erst angelegt? Dann einmal frisch rechnen.
    if (!one(ctx.db, 'SELECT 1 AS x FROM creators WHERE uuid = ?', creator)) throw notFound('creator_not_found', 'Creator not found')
    clearCreatorStatsCache(ctx)
    row = snapshot(ctx).rows.find((r) => r.uuid === creator)
    if (!row) throw notFound('creator_not_found', 'Creator not found')
  }
  const today = Math.floor(ctx.now() / DAY)
  const first = today - (q.days - 1)
  const days = Array.from({ length: q.days }, (_, i) => dayStr(first + i))
  const daily = new Map(all<{ day: string, new_fans: number, redemptions: number, active_fans: number | null, playing_fans: number | null }>(
    ctx.db,
    'SELECT day, new_fans, redemptions, active_fans, playing_fans FROM creator_daily WHERE creator_uuid = ? AND day >= ?',
    creator, days[0]!,
  ).map((r) => [r.day, r]))
  const perDay = (sql: string, ...p: (string | number)[]) =>
    new Map(all<{ day: string, n: number }>(ctx.db, sql, ...p).map((r) => [r.day, r.n]))
  const newPlayers = perDay(
    `SELECT strftime('%Y-%m-%d', f.first / 1000, 'unixepoch') AS day, COUNT(*) AS n
     FROM (SELECT r.fan_uuid, MIN(r.redeemed_at) AS first FROM creator_code_redemptions r
           JOIN creator_codes k ON k.id = r.code_id WHERE k.creator_uuid = ? GROUP BY r.fan_uuid) f
     JOIN users u ON u.uuid = f.fan_uuid
     WHERE f.first - u.created_at <= ? AND f.first >= ? GROUP BY day`,
    creator, NEW_PLAYER_WINDOW_MS, first * DAY,
  )
  const petRedemptions = perDay(
    `SELECT strftime('%Y-%m-%d', r.redeemed_at / 1000, 'unixepoch') AS day, COUNT(*) AS n
     FROM codes k JOIN code_redemptions r ON r.code_id = k.id
     WHERE k.creator_uuid = ? AND r.redeemed_at >= ? GROUP BY day`,
    creator, first * DAY,
  )
  // Ab dem ersten Schnappschuss DIESES Creators gibt es Werte; Tage ohne Zeile danach = 0 aktive Fans.
  const since = one<{ day: string | null }>(
    ctx.db, 'SELECT MIN(day) AS day FROM creator_daily WHERE creator_uuid = ? AND active_fans IS NOT NULL', creator,
  )!.day
  const series = days.map((day): StatDay => {
    const d = daily.get(day)
    const tracked = since !== null && day >= since
    return {
      day,
      newFans: d?.new_fans ?? 0,
      newPlayers: newPlayers.get(day) ?? 0,
      redemptions: d?.redemptions ?? 0,
      petRedemptions: petRedemptions.get(day) ?? 0,
      activeFans: tracked ? (d?.active_fans ?? 0) : null,
      playingFans: tracked ? (d?.playing_fans ?? 0) : null,
    }
  })
  const n = (sql: string, ...p: (string | number)[]) => one<{ n: number }>(ctx.db, sql, ...p)!.n
  return {
    generatedAt: iso(s.at),
    definitions: definitions(s.activitySince),
    creator: row,
    fanCape: {
      holders: n(
        'SELECT COUNT(DISTINCT g.uuid) AS n FROM user_capes g JOIN capes c ON c.id = g.cape_id WHERE c.creator_uuid = ? AND g.uuid <> ?',
        creator, creator,
      ),
      fanHolders: n(
        `SELECT COUNT(*) AS n FROM creator_supports s WHERE s.creator_uuid = ? AND EXISTS (
           SELECT 1 FROM user_capes g JOIN capes c ON c.id = g.cape_id WHERE g.uuid = s.fan_uuid AND c.creator_uuid = ?)`,
        creator, creator,
      ),
    },
    series,
  }
}

// ---------------------------------------------------------------- Tages-Schnappschuss der Fan-Aktivität

/**
 * Alle 10 min (00.context.ts): je aktivem Creator die aktuellen Fans mit Präsenz bzw. Spiel HEUTE in `creator_daily`
 * (`active_fans`/`playing_fans`). Über den Tag zählt das Maximum – wer heute schon gezählt wurde, bleibt gezählt, auch
 * wenn er später den Creator wechselt. Fans, die erst in den letzten Minuten vor Mitternacht (UTC) aktiv werden, fehlen
 * im Tageswert (zählen aber in `activity` der Rangliste). Liefert die Zahl der geschriebenen Zeilen.
 */
export function snapshotCreatorActivity(ctx: AppContext): number {
  const today = Math.floor(ctx.now() / DAY)
  return run(
    ctx.db,
    `INSERT INTO creator_daily (creator_uuid, day, active_fans, playing_fans)
     SELECT c.uuid, ?, COALESCE(a.online, 0), COALESCE(a.playing, 0) FROM creators c
     LEFT JOIN (SELECT s.creator_uuid AS cu, COUNT(*) AS online, SUM(COALESCE(t.last_played_day, -1) = ?) AS playing
                FROM achievement_playtime t JOIN creator_supports s ON s.fan_uuid = t.uuid
                WHERE t.last_day = ? AND s.creator_uuid IS NOT NULL GROUP BY s.creator_uuid) a ON a.cu = c.uuid
     WHERE c.state = 'active'
     ON CONFLICT(creator_uuid, day) DO UPDATE SET
       active_fans = MAX(COALESCE(active_fans, 0), excluded.active_fans),
       playing_fans = MAX(COALESCE(playing_fans, 0), excluded.playing_fans)`,
    dayStr(today), today, today,
  )
}
