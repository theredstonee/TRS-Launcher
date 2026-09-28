import { z } from 'zod'
import {
  ACHIEVEMENT_ORDER,
  ACHIEVEMENTS,
  REPORTED_METRICS,
  maxGoal,
  type AchievementCategory,
  type AchievementDef,
  type GoalUnit,
  type Metric,
  type Rarity,
  type Reward,
  type Texts,
} from './achievement-catalog'
import { grantCape } from './capes'
import type { AppContext } from './context'
import { grantCosmetic } from './cosmetics'
import { all, one, run } from './db'
import { notFound } from './errors'
import { areFriends, hasBlocked } from './friends'
import { getUser, isBanned } from './users'

/**
 * Erfolge (§31). Fortschritt wird wo möglich aus vorhandenen Tabellen abgeleitet (rückwirkend, nie doppelt), sonst
 * aus Zählern in `achievement_stats` (Launcher-Meldungen und Dinge, die später verschwinden). Freischalten ist
 * idempotent (PRIMARY KEY), Belohnungen werden nachgereicht, sobald das Teil existiert.
 */

const DAY_MS = 24 * 60 * 60 * 1000
const iso = (t: number) => new Date(t).toISOString()

// ---------------------------------------------------------------- Ansichten

export interface AchievementView {
  id: string
  category: AchievementCategory
  secret: boolean
  /** Geheim und vom Betrachter nicht freigeschaltet: ohne Texte/Belohnung/Ziel, Symbol `secret`. */
  hidden: boolean
  title: Texts | null
  description: Texts | null
  icon: string
  points: number
  rarity: Rarity
  goal: number | null
  unit: GoalUnit | null
  /** Vom Server gezählt (`false` = Launcher-Meldung, §31.4). */
  verified: boolean
  reward: Reward | null
  order: number
}

export interface UnlockView {
  id: string
  at: string
}

export interface AchievementUnlockedEvent {
  type: 'achievement_unlocked'
  achievement: AchievementView
  at: string
  /** Mit dieser Freischaltung vergebenes Teil (`null` = keine Belohnung oder das Teil gibt es noch nicht). */
  reward: Reward | null
}

export function achievementView(a: AchievementDef, revealed: boolean): AchievementView {
  const hidden = a.secret === true && !revealed
  return {
    id: a.id,
    category: a.category,
    secret: a.secret === true,
    hidden,
    title: hidden ? null : { ...a.title },
    description: hidden ? null : { ...a.description },
    icon: hidden ? 'secret' : a.icon,
    points: a.points,
    rarity: a.rarity,
    goal: hidden || a.goal === undefined ? null : a.goal,
    unit: hidden || a.goal === undefined ? null : (a.unit ?? 'count'),
    verified: !REPORTED_METRICS.has(a.metric),
    reward: hidden || !a.reward ? null : { ...a.reward },
    order: ACHIEVEMENT_ORDER.get(a.id)!,
  }
}

/** Öffentlicher Katalog (`GET /v1/achievements`): geheime Erfolge immer verborgen. */
export function publicCatalog(): { achievements: AchievementView[] } {
  return { achievements: ACHIEVEMENTS.map((a) => achievementView(a, false)) }
}

export const TOTAL_POINTS = ACHIEVEMENTS.reduce((s, a) => s + a.points, 0)

// ---------------------------------------------------------------- Zähler

/** Zählt einen gespeicherten Zähler hoch, höchstens bis zum höchsten Ziel, das ihn nutzt. */
function bumpStat(ctx: AppContext, uuid: string, key: Metric, by = 1): void {
  const cap = maxGoal(key)
  run(
    ctx.db,
    `INSERT INTO achievement_stats (uuid, key, value, updated_at) VALUES (?, ?, MIN(?, ?), ?)
     ON CONFLICT(uuid, key) DO UPDATE SET value = MIN(value + excluded.value, ?), updated_at = excluded.updated_at`,
    uuid, key, by, cap, ctx.now(), cap,
  )
}

function stat(ctx: AppContext, uuid: string, key: Metric): number {
  return one<{ value: number }>(ctx.db, 'SELECT value FROM achievement_stats WHERE uuid = ? AND key = ?', uuid, key)?.value ?? 0
}

interface PlaytimeRow {
  played_ms: number
  last_beat_at: number | null
  session_ms: number
  best_session_ms: number
  last_day: number | null
  streak: number
  best_streak: number
}

function playtime(ctx: AppContext, uuid: string): PlaytimeRow | undefined {
  return one<PlaytimeRow>(ctx.db, 'SELECT * FROM achievement_playtime WHERE uuid = ?', uuid)
}

const exists = (ctx: AppContext, sql: string, ...params: (string | number)[]) => (one(ctx.db, sql, ...params) !== undefined ? 1 : 0)

/** Zählt höchstens `cap` Zeilen (bleibt billig, auch bei vielen Nachrichten). */
function countUpTo(ctx: AppContext, cap: number, inner: string, ...params: (string | number)[]): number {
  return one<{ n: number }>(ctx.db, `SELECT COUNT(*) AS n FROM (${inner} LIMIT ?)`, ...params, cap)!.n
}

type Getter = (ctx: AppContext, uuid: string) => number

/**
 * Abgeleitete Werte je Messgröße. Messgrößen mit Zähler (`STORED`) nehmen das Maximum aus Zähler und Ableitung – so
 * zählen auch Dinge von vor der Einführung (Packs, Welten, Screenshots), die später ablaufen.
 */
const DERIVED: Partial<Record<Metric, Getter>> = {
  play_minutes: (ctx, u) => Math.floor((playtime(ctx, u)?.played_ms ?? 0) / 60_000),
  best_session_minutes: (ctx, u) => Math.floor((playtime(ctx, u)?.best_session_ms ?? 0) / 60_000),
  best_streak_days: (ctx, u) => playtime(ctx, u)?.best_streak ?? 0,
  friends: (ctx, u) => one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM friendships WHERE a = ? OR b = ?', u, u)!.n,
  chat_messages: (ctx, u) =>
    countUpTo(ctx, maxGoal('chat_messages'), "SELECT 1 FROM chat_messages WHERE sender_uuid = ? AND kind = 'text'", u),
  issues_opened: (ctx, u) =>
    countUpTo(ctx, maxGoal('issues_opened'), 'SELECT 1 FROM issues WHERE author_uuid = ? AND deleted_at IS NULL', u),
  bug_from_game_flag: (ctx, u) =>
    exists(ctx, "SELECT 1 AS x FROM issues WHERE author_uuid = ? AND source = 'client' AND deleted_at IS NULL LIMIT 1", u),
  ideas_done: (ctx, u) =>
    exists(
      ctx,
      `SELECT 1 AS x FROM issues WHERE author_uuid = ? AND type = 'feature' AND deleted_at IS NULL
       AND (status = 'done' OR fixed_in IS NOT NULL) LIMIT 1`,
      u,
    ),
  bugs_fixed: (ctx, u) =>
    exists(
      ctx,
      `SELECT 1 AS x FROM issues WHERE author_uuid = ? AND type = 'bug' AND deleted_at IS NULL
       AND (status = 'done' OR fixed_in IS NOT NULL) LIMIT 1`,
      u,
    ),
  upvotes_received: (ctx, u) =>
    countUpTo(
      ctx,
      maxGoal('upvotes_received'),
      `SELECT 1 FROM issue_votes v JOIN issues i ON i.id = v.issue_id
       WHERE i.author_uuid = ? AND i.deleted_at IS NULL AND v.vote = 1 AND v.uuid <> ?`,
      u, u,
    ),
  votes_cast: (ctx, u) => countUpTo(ctx, maxGoal('votes_cast'), 'SELECT 1 FROM issue_votes WHERE uuid = ?', u),
  circuits_approved: (ctx, u) =>
    countUpTo(ctx, maxGoal('circuits_approved'), "SELECT 1 FROM circuits WHERE author_uuid = ? AND source = 'submission'", u),
  packs_shared: (ctx, u) => countUpTo(ctx, maxGoal('packs_shared'), 'SELECT 1 FROM shared_packs WHERE owner_uuid = ?', u),
  pack_installs: (ctx, u) =>
    one<{ n: number | null }>(ctx.db, 'SELECT SUM(installs) AS n FROM shared_packs WHERE owner_uuid = ?', u)?.n ?? 0,
  worlds_hosted: (ctx, u) => exists(ctx, 'SELECT 1 AS x FROM hosting_rooms WHERE host_uuid = ? LIMIT 1', u),
  screenshots_shared: (ctx, u) => exists(ctx, 'SELECT 1 AS x FROM shared_images WHERE owner_uuid = ? LIMIT 1', u),
  cape_worn_flag: (ctx, u) => exists(ctx, 'SELECT 1 AS x FROM users WHERE uuid = ? AND active_cape_id IS NOT NULL', u),
  cosmetic_equipped_flag: (ctx, u) => exists(ctx, 'SELECT 1 AS x FROM equipped_cosmetics WHERE uuid = ? LIMIT 1', u),
  duck_flag: (ctx, u) => exists(ctx, "SELECT 1 AS x FROM user_cosmetics WHERE uuid = ? AND cosmetic_id = 'rubber_duck'", u),
  codes_redeemed: (ctx, u) => countUpTo(ctx, maxGoal('codes_redeemed'), 'SELECT 1 FROM code_redemptions WHERE uuid = ?', u),
}

/** Messgrößen mit gespeichertem Zähler/Flag (Launcher-Meldungen + Server-Zähler für Vergängliches). */
const STORED: ReadonlySet<Metric> = new Set<Metric>([
  ...REPORTED_METRICS,
  'packs_shared',
  'pack_installs',
  'worlds_hosted',
  'screenshots_shared',
])

function metricValue(ctx: AppContext, uuid: string, m: Metric): number {
  const derived = DERIVED[m]?.(ctx, uuid) ?? 0
  return STORED.has(m) ? Math.max(derived, stat(ctx, uuid, m)) : derived
}

// ---------------------------------------------------------------- Belohnungen

const warned = new WeakMap<AppContext, Set<string>>()

/** Gibt es das Belohnungs-Teil (mitgeliefert, nicht ausgemustert)? Fehlt es, einmal je Prozess ins Log. */
function rewardAvailable(ctx: AppContext, r: Reward): boolean {
  const table = r.kind === 'cape' ? 'capes' : 'cosmetics'
  // Tabellenname nur aus der festen Auswahl oben.
  const ok = one(ctx.db, `SELECT 1 AS x FROM ${table} WHERE id = ? AND kind = 'builtin' AND retired = 0`, r.id) !== undefined
  if (!ok) {
    let seen = warned.get(ctx)
    if (!seen) {
      seen = new Set()
      warned.set(ctx, seen)
    }
    const key = `${r.kind}:${r.id}`
    if (!seen.has(key)) {
      seen.add(key)
      console.warn(`[trs-api] achievement reward ${key} does not exist yet – unlocks are kept and granted once it exists`)
    }
  }
  return ok
}

function grantReward(ctx: AppContext, uuid: string, achievementId: string, r: Reward): Reward | null {
  if (!rewardAvailable(ctx, r)) return null
  // Quelle 'admin': die Spalte kennt nur code/admin, Clients zeigen es wie eine Vergabe durch das Team.
  if (r.kind === 'cape') grantCape(ctx, uuid, r.id, 'admin')
  else grantCosmetic(ctx, uuid, r.id, 'admin')
  run(
    ctx.db,
    'UPDATE achievement_unlocks SET reward_granted_at = ? WHERE uuid = ? AND achievement_id = ? AND reward_granted_at IS NULL',
    ctx.now(), uuid, achievementId,
  )
  return { ...r }
}

/**
 * Nachreichen: Belohnungen für schon freigeschaltete Erfolge, deren Teil es inzwischen gibt (nach dem Start, alle
 * 10 min, beim eigenen Abruf). Ohne `uuid` für alle Konten. Rückgabe: Anzahl vergebener Teile.
 */
export function grantPendingRewards(ctx: AppContext, uuid?: string): number {
  let n = 0
  for (const a of ACHIEVEMENTS) {
    if (!a.reward) continue
    const rows = uuid === undefined
      ? all<{ uuid: string }>(ctx.db, 'SELECT uuid FROM achievement_unlocks WHERE achievement_id = ? AND reward_granted_at IS NULL', a.id)
      : all<{ uuid: string }>(
        ctx.db,
        'SELECT uuid FROM achievement_unlocks WHERE achievement_id = ? AND reward_granted_at IS NULL AND uuid = ?',
        a.id, uuid,
      )
    if (rows.length === 0 || !rewardAvailable(ctx, a.reward)) continue
    for (const r of rows) {
      if (grantReward(ctx, r.uuid, a.id, a.reward)) n++
    }
  }
  return n
}

// ---------------------------------------------------------------- Freischalten

function unlockedMap(ctx: AppContext, uuid: string): Map<string, number> {
  return new Map(
    all<{ achievement_id: string, unlocked_at: number }>(
      ctx.db,
      'SELECT achievement_id, unlocked_at FROM achievement_unlocks WHERE uuid = ?',
      uuid,
    ).map((r) => [r.achievement_id, r.unlocked_at]),
  )
}

function unlock(ctx: AppContext, uuid: string, a: AchievementDef): UnlockView | null {
  const t = ctx.now()
  const n = run(
    ctx.db,
    'INSERT INTO achievement_unlocks (uuid, achievement_id, unlocked_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING',
    uuid, a.id, t,
  )
  if (n === 0) return null
  const reward = a.reward ? grantReward(ctx, uuid, a.id, a.reward) : null
  const e: AchievementUnlockedEvent = { type: 'achievement_unlocked', achievement: achievementView(a, true), at: iso(t), reward }
  ctx.events.publish(uuid, e, { meOnly: true })
  return { id: a.id, at: iso(t) }
}

const SECRET_IDS = ACHIEVEMENTS.filter((a) => a.secret).map((a) => a.id)

/**
 * Prüft die Erfolge eines Kontos und schaltet frei, was erreicht ist. `metrics` = nur Erfolge dieser Messgrößen
 * (Hooks), ohne = alle. Der Meta-Erfolg (geheime zählen) läuft immer am Ende mit. Rückgabe: neu freigeschaltet.
 */
export function checkAchievements(ctx: AppContext, uuid: string, metrics?: readonly Metric[]): UnlockView[] {
  const have = unlockedMap(ctx, uuid)
  const memo = new Map<Metric, number>()
  const value = (m: Metric): number => {
    let v = memo.get(m)
    if (v === undefined) {
      v = metricValue(ctx, uuid, m)
      memo.set(m, v)
    }
    return v
  }
  const out: UnlockView[] = []
  const take = (a: AchievementDef) => {
    const u = unlock(ctx, uuid, a)
    if (u) {
      out.push(u)
      have.set(a.id, ctx.now())
    }
  }
  for (const a of ACHIEVEMENTS) {
    if (a.metric === 'secrets_unlocked' || have.has(a.id)) continue
    if (metrics && !metrics.includes(a.metric)) continue
    if (value(a.metric) >= (a.goal ?? 1)) take(a)
  }
  for (const a of ACHIEVEMENTS) {
    if (a.metric !== 'secrets_unlocked' || have.has(a.id)) continue
    if (SECRET_IDS.filter((id) => have.has(id)).length >= (a.goal ?? SECRET_IDS.length)) take(a)
  }
  return out
}

/** Für Hooks in fremden Abläufen: Fehler hier dürfen die eigentliche Anfrage nie scheitern lassen. */
export function touchAchievements(ctx: AppContext, uuid: string, metrics: readonly Metric[]): void {
  try {
    checkAchievements(ctx, uuid, metrics)
  } catch (err) {
    console.error('[trs-api] achievement check failed', (err as Error).message)
  }
}

/** Issue geändert (Stimme, Status, „Erledigt in“): Ersteller prüfen (erhaltene Stimmen, umgesetzt/behoben). */
export function touchIssueAuthor(ctx: AppContext, issueNumber: number): void {
  try {
    const author = one<{ author_uuid: string | null }>(ctx.db, 'SELECT author_uuid FROM issues WHERE id = ?', issueNumber)?.author_uuid
    if (author) checkAchievements(ctx, author, ['upvotes_received', 'ideas_done', 'bugs_fixed'])
  } catch (err) {
    console.error('[trs-api] achievement check failed', (err as Error).message)
  }
}

/** Schaltungs-Einreichung angenommen: Einreicher prüfen. */
export function touchCircuitSubmitter(ctx: AppContext, submissionId: string): void {
  try {
    const uuid = one<{ uuid: string }>(ctx.db, 'SELECT uuid FROM circuit_submissions WHERE id = ?', submissionId)?.uuid
    if (uuid) checkAchievements(ctx, uuid, ['circuits_approved'])
  } catch (err) {
    console.error('[trs-api] achievement check failed', (err as Error).message)
  }
}

/** Server-Zähler hochzählen (geteilte Packs, Installationen, Welten, Screenshots) und prüfen. */
export function countAchievement(ctx: AppContext, uuid: string, metric: 'packs_shared' | 'pack_installs' | 'worlds_hosted' | 'screenshots_shared'): void {
  try {
    bumpStat(ctx, uuid, metric)
    checkAchievements(ctx, uuid, [metric])
  } catch (err) {
    console.error('[trs-api] achievement counter failed', (err as Error).message)
  }
}

// ---------------------------------------------------------------- Spielzeit (Herzschläge, §31.7)

/**
 * Herzschlag aus `POST /v1/presence`. `inGame` = zusammengeführte Präsenz nach der Meldung. Gutgeschrieben wird die
 * Zeit seit dem letzten In-Game-Herzschlag des Kontos (egal welcher Quelle), höchstens `presenceTtlMs` je Lücke –
 * eine längere Lücke beginnt eine neue Sitzung ohne Gutschrift. `active` = zählt für die Tages-Serie (nicht `offline`).
 */
export function recordHeartbeat(ctx: AppContext, uuid: string, inGame: boolean, active: boolean): void {
  const t = ctx.now()
  const ttl = ctx.config.limits.presenceTtlMs
  const row = playtime(ctx, uuid)
  let played = row?.played_ms ?? 0
  let session = row?.session_ms ?? 0
  let bestSession = row?.best_session_ms ?? 0
  let last = row?.last_beat_at ?? null
  if (inGame) {
    if (last !== null && t - last <= ttl) {
      const d = Math.max(0, t - last)
      played += d
      session += d
    } else {
      session = 0
    }
    bestSession = Math.max(bestSession, session)
    last = t
  } else {
    last = null
    session = 0
  }
  let lastDay = row?.last_day ?? null
  let streak = row?.streak ?? 0
  let bestStreak = row?.best_streak ?? 0
  if (active) {
    const day = Math.floor(t / DAY_MS)
    if (lastDay !== day) {
      streak = lastDay === day - 1 ? streak + 1 : 1
      lastDay = day
      bestStreak = Math.max(bestStreak, streak)
    }
  }
  run(
    ctx.db,
    `INSERT INTO achievement_playtime (uuid, played_ms, last_beat_at, session_ms, best_session_ms, last_day, streak, best_streak)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(uuid) DO UPDATE SET played_ms = excluded.played_ms, last_beat_at = excluded.last_beat_at,
       session_ms = excluded.session_ms, best_session_ms = excluded.best_session_ms, last_day = excluded.last_day,
       streak = excluded.streak, best_streak = excluded.best_streak`,
    uuid, played, last, session, bestSession, lastDay, streak, bestStreak,
  )
  checkAchievements(ctx, uuid, ['play_minutes', 'best_session_minutes', 'best_streak_days'])
}

// ---------------------------------------------------------------- Launcher-Meldungen (§31.4)

export const REPORT_KINDS = ['launch', 'mod_installed', 'modpack_installed', 'clip_recorded', 'crash_fixed', 'launcher_import'] as const
export type ReportKind = (typeof REPORT_KINDS)[number]

const COUNTED: ReadonlySet<ReportKind> = new Set<ReportKind>(['mod_installed', 'clip_recorded'])

export const achievementReportBody = z
  .strictObject({
    kind: z.enum(REPORT_KINDS),
    hour: z.number().int().min(0).max(23).optional(),
    count: z.number().int().min(1).max(100).optional(),
  })
  .superRefine((b, c) => {
    if (b.hour !== undefined && b.kind !== 'launch') {
      c.addIssue({ code: 'custom', path: ['hour'], message: 'only allowed with kind "launch"' })
    }
    if (b.count !== undefined && !COUNTED.has(b.kind)) {
      c.addIssue({ code: 'custom', path: ['count'], message: 'only allowed with kind "mod_installed" or "clip_recorded"' })
    }
  })

export type AchievementReport = z.output<typeof achievementReportBody>

/** Stunde (Ortszeit) des geheimen Nacht-Erfolgs: 3:00–3:59. */
const NIGHT_HOUR = 3

export function reportAchievement(ctx: AppContext, uuid: string, r: AchievementReport): { newlyUnlocked: UnlockView[] } {
  const touched: Metric[] = []
  const bump = (m: Metric, by = 1) => {
    bumpStat(ctx, uuid, m, by)
    touched.push(m)
  }
  switch (r.kind) {
    case 'launch':
      bump('launches')
      if (r.hour === NIGHT_HOUR) bump('night_launch_flag')
      break
    case 'mod_installed':
      bump('mods_installed', r.count ?? 1)
      break
    case 'modpack_installed':
      bump('modpacks_installed')
      break
    case 'clip_recorded':
      bump('clips_recorded', r.count ?? 1)
      break
    case 'crash_fixed':
      bump('crash_fixed_flag')
      break
    case 'launcher_import':
      bump('launcher_import_flag')
      break
  }
  return { newlyUnlocked: checkAchievements(ctx, uuid, touched) }
}

// ---------------------------------------------------------------- Abfragen

function unlockedList(ctx: AppContext, uuid: string): { list: UnlockView[], points: number, ids: Set<string> } {
  const rows = all<{ achievement_id: string, unlocked_at: number }>(
    ctx.db,
    'SELECT achievement_id, unlocked_at FROM achievement_unlocks WHERE uuid = ? ORDER BY unlocked_at, achievement_id',
    uuid,
  )
  const byId = new Map(ACHIEVEMENTS.map((a) => [a.id, a]))
  const known = rows.filter((r) => byId.has(r.achievement_id))
  return {
    list: known.map((r) => ({ id: r.achievement_id, at: iso(r.unlocked_at) })),
    points: known.reduce((s, r) => s + byId.get(r.achievement_id)!.points, 0),
    ids: new Set(known.map((r) => r.achievement_id)),
  }
}

export interface MyAchievements {
  achievements: AchievementView[]
  unlocked: UnlockView[]
  progress: Record<string, number>
  points: number
  totalPoints: number
}

/** `GET /v1/me/achievements`: erst nachreichen + alles prüfen, dann der Stand. */
export function myAchievements(ctx: AppContext, uuid: string): MyAchievements {
  grantPendingRewards(ctx, uuid)
  checkAchievements(ctx, uuid)
  const { list, points, ids } = unlockedList(ctx, uuid)
  const progress: Record<string, number> = {}
  const memo = new Map<Metric, number>()
  for (const a of ACHIEVEMENTS) {
    if (a.goal === undefined || (a.secret && !ids.has(a.id))) continue
    if (ids.has(a.id)) {
      progress[a.id] = a.goal
      continue
    }
    let v = memo.get(a.metric)
    if (v === undefined) {
      v = a.metric === 'secrets_unlocked' ? SECRET_IDS.filter((id) => ids.has(id)).length : metricValue(ctx, uuid, a.metric)
      memo.set(a.metric, v)
    }
    progress[a.id] = Math.min(a.goal, v)
  }
  return {
    achievements: ACHIEVEMENTS.map((a) => achievementView(a, ids.has(a.id))),
    unlocked: list,
    progress,
    points,
    totalPoints: TOTAL_POINTS,
  }
}

/**
 * `GET /v1/players/{uuid}/achievements`: nur man selbst oder angenommene Freunde. Unbekannt, gesperrt, blockiert (in
 * beide Richtungen) oder kein Freund → 404 `player_not_found` (verrät nichts).
 */
export function playerAchievements(ctx: AppContext, viewer: string, target: string): { unlocked: UnlockView[], points: number } {
  if (target !== viewer) {
    const denied = () => notFound('player_not_found', 'No friend with this UUID')
    if (!getUser(ctx, target) || isBanned(ctx, target)) throw denied()
    if (hasBlocked(ctx, target, viewer) || hasBlocked(ctx, viewer, target) || !areFriends(ctx, viewer, target)) throw denied()
  }
  const { list, points } = unlockedList(ctx, target)
  return { unlocked: list, points }
}
