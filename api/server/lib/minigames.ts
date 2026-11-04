import { randomInt, randomUUID } from 'node:crypto'
import { z } from 'zod'
import { GAMES, MAX_INPUTS, replay, validInputs, type GameId } from '../../shared/minigames/replay'
import { assertCan, type Staff } from './team'
import { audit } from './audit'
import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { badRequest, notFound } from './errors'

/**
 * Minispiel-Ranglisten (§42). Der Server vergibt den Startwert und rechnet jede eingereichte Partie nach
 * (`shared/minigames`, dieselben Regeln wie Launcher und TRS Client). Gewertet wird der nachgerechnete Score.
 */

/** Ungenutzte Partien verfallen nach 3 Stunden (eine Partie dauert höchstens eine Stunde). */
export const RUN_TTL_MS = 3 * 60 * 60 * 1000
/** Benutzte Zeilen bleiben einen Tag liegen. */
const USED_KEEP_MS = 24 * 60 * 60 * 1000
/** Takt des Spiels (Hz) – siehe `shared/minigames`. */
const TICK_HZ = 60
/** Eine Partie darf höchstens um 10 % plus 5 s schneller eingereicht werden, als sie dauert. */
const SLACK_FACTOR = 1.1
const SLACK_MS = 5000
export const LEADERBOARD_SIZE = 100

export const gameSchema = z.enum(GAMES)

export const submitBody = z.object({
  runId: z.string().regex(/^[A-Za-z0-9-]{1,64}$/),
  // Takt-Abstand und Tastenmaske abwechselnd; die Spielregeln prüft `validInputs` zusätzlich.
  inputs: z.array(z.number().int().min(0).max(1_000_000)).max(MAX_INPUTS),
  score: z.number().int().min(0).max(1_000_000_000),
}).strict()

export const removeBody = z.object({
  reason: z.string().trim().min(1).max(300).refine((s) => !/[\p{Cc}\p{Cf}\p{Co}\p{Cn}]/u.test(s), 'must not contain control characters'),
}).strict()

/** Body-Grenze beim Einreichen: 36 000 Zahlen à höchstens 7 Zeichen plus Komma, mit Puffer. */
export const SUBMIT_BODY_MAX = 512 * 1024

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

export function startRun(ctx: AppContext, uuid: string, game: GameId): { runId: string, seed: number } {
  const t = ctx.now()
  run(ctx.db, 'DELETE FROM minigame_runs WHERE (used = 0 AND created_at < ?) OR (used = 1 AND created_at < ?)', t - RUN_TTL_MS, t - USED_KEEP_MS)
  const runId = randomUUID()
  // uint32: [0, 2^32) liegt im Bereich von randomInt.
  const seed = randomInt(0, 0x1_0000_0000)
  run(ctx.db, 'INSERT INTO minigame_runs (run_id, uuid, game, seed, created_at, used) VALUES (?, ?, ?, ?, ?, 0)', runId, uuid, game, seed, t)
  return { runId, seed }
}

export interface SubmitResult {
  accepted: boolean
  rank: number
  best: number
  improved: boolean
}

const REJECTED: SubmitResult = { accepted: false, rank: 0, best: 0, improved: false }

interface RunRow { uuid: string, game: string, seed: number, created_at: number, used: number }

/** Gesperrte Konten (aktive Strafe `account_ban`, wie `isBanned`) stehen nicht in der Liste und zählen nicht mit. Alias `s` = minigame_scores; ein Parameter (jetzt). */
const NOT_BANNED = `NOT EXISTS (SELECT 1 FROM sanctions z WHERE z.uuid = s.uuid AND z.kind = 'account_ban' AND z.lifted_at IS NULL AND (z.expires_at IS NULL OR z.expires_at > ?))`

/** Platz = 1 + Einträge davor: höhere Punktzahl, bei Gleichstand früher erreicht, dann UUID (fest). */
export function rankOf(ctx: AppContext, game: GameId, uuid: string): { rank: number, score: number } | null {
  const me = one<{ score: number, at: number }>(ctx.db, 'SELECT score, at FROM minigame_scores WHERE game = ? AND uuid = ?', game, uuid)
  if (!me) return null
  const ahead = one<{ n: number }>(
    ctx.db,
    `SELECT COUNT(*) AS n FROM minigame_scores s
     WHERE s.game = ? AND (s.score > ? OR (s.score = ? AND s.at < ?) OR (s.score = ? AND s.at = ? AND s.uuid < ?)) AND ${NOT_BANNED}`,
    game, me.score, me.score, me.at, me.score, me.at, uuid, ctx.now(),
  )!.n
  return { rank: ahead + 1, score: me.score }
}

/**
 * Partie einreichen. Die Partie wird vor dem Nachrechnen verbraucht (einmalig, atomar), damit sie nie mehrfach
 * probiert werden kann. Unbekannte, fremde, benutzte oder abgelaufene Partien → 404.
 */
export function submitRun(ctx: AppContext, uuid: string, game: GameId, raw: unknown): SubmitResult {
  const body = parse(submitBody, raw)
  const t = ctx.now()
  const row = tx(ctx.db, () => {
    const r = one<RunRow>(ctx.db, 'SELECT uuid, game, seed, created_at, used FROM minigame_runs WHERE run_id = ?', body.runId)
    if (!r || r.uuid !== uuid || r.game !== game || r.used !== 0 || t - r.created_at > RUN_TTL_MS) return null
    return run(ctx.db, 'UPDATE minigame_runs SET used = 1 WHERE run_id = ? AND used = 0', body.runId) === 1 ? r : null
  })
  if (!row) throw notFound('minigame_run_unknown', 'This run does not exist or was already used')

  if (!validInputs(game, body.inputs)) return { ...REJECTED }
  // Synchron im Hauptthread; die Last begrenzt das Rate-Limit `minigameSubmitUser`.
  const result = replay(game, row.seed, body.inputs)
  if (!result.finished || result.score !== body.score) return { ...REJECTED }
  // Zu schnell: `ticks / 60` Sekunden müssen seit dem Start vergangen sein (10 % + 5 s Spielraum).
  if ((result.ticks / TICK_HZ) * 1000 > (t - row.created_at) * SLACK_FACTOR + SLACK_MS) return { ...REJECTED }

  const improved = tx(ctx.db, () => {
    const old = one<{ score: number }>(ctx.db, 'SELECT score FROM minigame_scores WHERE game = ? AND uuid = ?', game, uuid)
    if (old && old.score >= result.score) return false
    run(
      ctx.db,
      `INSERT INTO minigame_scores (game, uuid, score, ticks, at) VALUES (?, ?, ?, ?, ?)
       ON CONFLICT (game, uuid) DO UPDATE SET score = excluded.score, ticks = excluded.ticks, at = excluded.at`,
      game, uuid, result.score, result.ticks, t,
    )
    return true
  })
  const mine = rankOf(ctx, game, uuid)!
  return { accepted: true, rank: mine.rank, best: mine.score, improved }
}

export interface LeaderboardView {
  entries: { rank: number, uuid: string, name: string, score: number, at: string }[]
  me: { rank: number, score: number } | null
  total: number
}

export function leaderboard(ctx: AppContext, uuid: string, game: GameId): LeaderboardView {
  const rows = all<{ uuid: string, name: string, score: number, at: number }>(
    ctx.db,
    `SELECT s.uuid, u.name, s.score, s.at FROM minigame_scores s JOIN users u ON u.uuid = s.uuid
     WHERE s.game = ? AND ${NOT_BANNED} ORDER BY s.score DESC, s.at ASC, s.uuid ASC LIMIT ?`,
    game, ctx.now(), LEADERBOARD_SIZE,
  )
  const total = one<{ n: number }>(ctx.db, `SELECT COUNT(*) AS n FROM minigame_scores s WHERE s.game = ? AND ${NOT_BANNED}`, game, ctx.now())!.n
  return {
    entries: rows.map((r, i) => ({ rank: i + 1, uuid: r.uuid, name: r.name, score: r.score, at: iso(r.at) })),
    me: rankOf(ctx, game, uuid),
    total,
  }
}

export function removeScore(ctx: AppContext, staff: Staff, game: GameId, uuid: string, raw: unknown): void {
  assertCan(staff, 'minigames.manage')
  const { reason } = parse(removeBody, raw)
  tx(ctx.db, () => {
    const row = one<{ score: number }>(ctx.db, 'SELECT score FROM minigame_scores WHERE game = ? AND uuid = ?', game, uuid)
    if (!row) throw notFound('minigame_score_unknown', 'No score for this player')
    run(ctx.db, 'DELETE FROM minigame_scores WHERE game = ? AND uuid = ?', game, uuid)
    audit(ctx, staff.uuid, 'minigames.score.remove', uuid, `${game} (${row.score}): ${reason}`)
  })
}
