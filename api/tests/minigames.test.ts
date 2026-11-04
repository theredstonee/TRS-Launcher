import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { DatabaseSync } from 'node:sqlite'
import { describe, expect, it } from 'vitest'
import { GAMES, MAX_INPUTS, replay, validInputs, type GameId } from '../shared/minigames/replay'
import { banUser, unbanUser } from '../server/lib/admin'
import { all, one, run } from '../server/lib/db'
import { migrateMinigames } from '../server/lib/migrations'
import { leaderboard, removeScore, startRun, submitRun, RUN_TTL_MS } from '../server/lib/minigames'
import { ownerStaff, type Staff } from '../server/lib/team'
import { code } from './chathelpers'
import { ADMIN, login, makeEnv, type TestEnv } from './helpers'

interface Vector { game: GameId, seed: number, inputs: number[], score: number, ticks: number, hash: number }
const VECTORS = JSON.parse(readFileSync(join(__dirname, 'fixtures', 'minigames-vectors.json'), 'utf8')) as Vector[]
const shortest = (game: GameId) => VECTORS.filter((v) => v.game === game).sort((a, b) => a.ticks - b.ticks)[0]!

const OWNER = ownerStaff(ADMIN)
const NO_RIGHT: Staff = { ...OWNER, uuid: 'b'.repeat(32), owner: false, rank: 100, perms: new Set(), roles: ['supporter'] }
const WITH_RIGHT: Staff = { ...NO_RIGHT, perms: new Set(['minigames.manage']) }

const player = async (env: TestEnv, name: string) => (await login(env, name)).user.uuid

/** Partie starten, den Startwert auf den der Testpartie setzen und die Spielzeit verstreichen lassen. */
function prepared(env: TestEnv, uuid: string, v: Vector, waitMs = (v.ticks / 60) * 1000) {
  const { runId } = startRun(env.ctx, uuid, v.game)
  run(env.ctx.db, 'UPDATE minigame_runs SET seed = ? WHERE run_id = ?', v.seed, runId)
  env.clock.advance(waitMs)
  return { runId, body: { runId, inputs: v.inputs, score: v.score } }
}

/** Bestwert eines Spielers mit festem Punktestand direkt eintragen (Rangfolge-Tests). */
function putScore(env: TestEnv, game: GameId, uuid: string, score: number, at: number) {
  run(env.ctx.db, 'INSERT INTO minigame_scores (game, uuid, score, ticks, at) VALUES (?, ?, ?, 100, ?)', game, uuid, score, at)
}

describe('shared replay vectors', () => {
  it('recomputes every test game exactly (score, ticks, hash)', () => {
    expect(VECTORS.length).toBeGreaterThanOrEqual(28)
    for (const v of VECTORS) {
      expect(validInputs(v.game, v.inputs)).toBe(true)
      const r = replay(v.game, v.seed, v.inputs)
      expect({ game: v.game, seed: v.seed, score: r.score, ticks: r.ticks, hash: r.hash, finished: r.finished })
        .toEqual({ game: v.game, seed: v.seed, score: v.score, ticks: v.ticks, hash: v.hash, finished: true })
    }
    expect(new Set(VECTORS.map((v) => v.game))).toEqual(new Set(GAMES))
  })

  it('rejects malformed input lists', () => {
    expect(validInputs('stapler', [1])).toBe(false)
    expect(validInputs('stapler', [0, 256])).toBe(false)
    expect(validInputs('loren', [0, 32])).toBe(false)
    expect(validInputs('loren', [-1, 0])).toBe(false)
    expect(validInputs('loren', [1.5, 0])).toBe(false)
    expect(validInputs('loren', new Array(MAX_INPUTS + 2).fill(0))).toBe(false)
  })
})

describe('start', () => {
  it('gives a random uint32 seed, own run ids, and cleans up old runs', async () => {
    const env = makeEnv()
    const a = await player(env, 'Alpha')
    const r1 = startRun(env.ctx, a, 'stapler')
    const r2 = startRun(env.ctx, a, 'loren')
    expect(r1.runId).not.toBe(r2.runId)
    for (const r of [r1, r2]) {
      expect(Number.isInteger(r.seed) && r.seed >= 0 && r.seed <= 0xFFFFFFFF).toBe(true)
    }
    // Eine benutzte und eine unbenutzte alte Partie.
    run(env.ctx.db, 'UPDATE minigame_runs SET used = 1 WHERE run_id = ?', r2.runId)
    env.clock.advance(RUN_TTL_MS + 1000)
    const r3 = startRun(env.ctx, a, 'stapler')
    expect(all<{ run_id: string }>(env.ctx.db, 'SELECT run_id FROM minigame_runs').map((r) => r.run_id).sort()).toEqual([r2.runId, r3.runId].sort())
  })
})

describe('submit', () => {
  it('accepts a valid game, stores the best, ranks it and reports improvements', async () => {
    const env = makeEnv()
    const a = await player(env, 'Alpha')
    const v = shortest('stapler')
    const res = submitRun(env.ctx, a, 'stapler', prepared(env, a, v).body)
    expect(res).toEqual({ accepted: true, rank: 1, best: v.score, improved: true })
    expect(one(env.ctx.db, 'SELECT score, ticks FROM minigame_scores WHERE game = ? AND uuid = ?', 'stapler', a)).toEqual({ score: v.score, ticks: v.ticks })

    // Schlechtere Partie: angenommen, aber kein neuer Bestwert.
    const worse = VECTORS.filter((x) => x.game === 'stapler' && x.score < v.score).sort((x, y) => x.ticks - y.ticks)[0]
    const hi = VECTORS.filter((x) => x.game === 'stapler' && x.score > v.score).sort((x, y) => x.ticks - y.ticks)[0]!
    if (worse) expect(submitRun(env.ctx, a, 'stapler', prepared(env, a, worse).body)).toEqual({ accepted: true, rank: 1, best: v.score, improved: false })
    expect(submitRun(env.ctx, a, 'stapler', prepared(env, a, hi).body)).toEqual({ accepted: true, rank: 1, best: hi.score, improved: true })
    expect(one(env.ctx.db, 'SELECT COUNT(*) AS n FROM minigame_scores')).toEqual({ n: 1 })
  })

  it('rejects a wrong score and burns the run', async () => {
    const env = makeEnv()
    const a = await player(env, 'Alpha')
    const v = shortest('loren')
    const p = prepared(env, a, v)
    expect(submitRun(env.ctx, a, 'loren', { ...p.body, score: v.score + 1 })).toEqual({ accepted: false, rank: 0, best: 0, improved: false })
    expect(one(env.ctx.db, 'SELECT COUNT(*) AS n FROM minigame_scores')).toEqual({ n: 0 })
    // Dieselbe Partie noch einmal mit dem richtigen Wert: verbraucht.
    expect(code(() => submitRun(env.ctx, a, 'loren', p.body))).toBe('minigame_run_unknown')
  })

  it('rejects an unfinished game and invalid input lists', async () => {
    const env = makeEnv()
    const a = await player(env, 'Alpha')
    const v = shortest('stapler')
    // Abgeschnittene Eingaben: das Spiel läuft mit der letzten Maske bis zum Ende oder bleibt unvollendet; Score stimmt dann nicht.
    const cut = prepared(env, a, v)
    const res = submitRun(env.ctx, a, 'stapler', { ...cut.body, inputs: v.inputs.slice(0, 20) })
    expect(res.accepted).toBe(false)
    const bad = prepared(env, a, v)
    expect(submitRun(env.ctx, a, 'stapler', { ...bad.body, inputs: [0, 999] }).accepted).toBe(false)
    expect(submitRun(env.ctx, a, 'stapler', { ...prepared(env, a, v).body, inputs: [1] }).accepted).toBe(false)
  })

  it('rejects other accounts, other games, unknown runs and doubles', async () => {
    const env = makeEnv()
    const a = await player(env, 'Alpha')
    const b = await player(env, 'Bravo')
    const v = shortest('stapler')
    const p = prepared(env, a, v)
    expect(code(() => submitRun(env.ctx, b, 'stapler', p.body))).toBe('minigame_run_unknown')
    expect(code(() => submitRun(env.ctx, a, 'loren', p.body))).toBe('minigame_run_unknown')
    expect(code(() => submitRun(env.ctx, a, 'stapler', { ...p.body, runId: 'does-not-exist' }))).toBe('minigame_run_unknown')
    // Fremde Partie hat die echte nicht verbraucht.
    expect(submitRun(env.ctx, a, 'stapler', p.body).accepted).toBe(true)
    expect(code(() => submitRun(env.ctx, a, 'stapler', p.body))).toBe('minigame_run_unknown')
    // Abgelaufen.
    const old = prepared(env, a, v, RUN_TTL_MS + 1)
    expect(code(() => submitRun(env.ctx, a, 'stapler', old.body))).toBe('minigame_run_unknown')
  })

  it('validates the body shape', async () => {
    const env = makeEnv()
    const a = await player(env, 'Alpha')
    const { runId } = startRun(env.ctx, a, 'stapler')
    for (const body of [null, {}, { runId, inputs: 'x', score: 1 }, { runId, inputs: [], score: -1 }, { runId, inputs: [], score: 1.5 }, { runId, inputs: [], score: 1, extra: 1 }, { runId: '../x', inputs: [], score: 1 }, { runId, inputs: new Array(MAX_INPUTS + 1).fill(0), score: 1 }]) {
      expect(code(() => submitRun(env.ctx, a, 'stapler', body))).toBe('invalid_request')
    }
    // Ungültige Form verbraucht die Partie nicht.
    expect(one(env.ctx.db, 'SELECT used FROM minigame_runs WHERE run_id = ?', runId)).toEqual({ used: 0 })
  })

  it('rejects a game submitted faster than it can last (10 % + 5 s)', async () => {
    const env = makeEnv()
    const a = await player(env, 'Alpha')
    const v = VECTORS.filter((x) => x.game === 'loren').sort((x, y) => y.ticks - x.ticks)[0]!
    const seconds = v.ticks / 60
    expect(seconds).toBeGreaterThan(30)
    // Sofort eingereicht.
    expect(submitRun(env.ctx, a, 'loren', prepared(env, a, v, 0).body).accepted).toBe(false)
    // Knapp zu schnell: Grenze ist elapsed*1.1 + 5 s >= Dauer, also elapsed >= (Dauer - 5 s) / 1.1.
    const limitMs = ((seconds * 1000) - 5000) / 1.1
    expect(submitRun(env.ctx, a, 'loren', prepared(env, a, v, limitMs - 50).body).accepted).toBe(false)
    expect(submitRun(env.ctx, a, 'loren', prepared(env, a, v, limitMs + 50).body).accepted).toBe(true)
  })
})

describe('leaderboard', () => {
  it('orders by score, earlier entry first on ties, with own rank, names and total', async () => {
    const env = makeEnv()
    const [a, b, c, d] = [await player(env, 'Alpha'), await player(env, 'Bravo'), await player(env, 'Charlie'), await player(env, 'Delta')]
    const t = env.clock.t
    putScore(env, 'stapler', a, 500, t + 3000)
    putScore(env, 'stapler', b, 900, t + 2000)
    putScore(env, 'stapler', c, 500, t + 1000) // Gleichstand mit Alpha, früher
    putScore(env, 'loren', d, 10, t)
    const board = leaderboard(env.ctx, a, 'stapler')
    expect(board.entries.map((e) => [e.rank, e.name, e.score])).toEqual([[1, 'Bravo', 900], [2, 'Charlie', 500], [3, 'Alpha', 500]])
    expect(board.entries[0]).toEqual({ rank: 1, uuid: b, name: 'Bravo', score: 900, at: new Date(t + 2000).toISOString() })
    expect(board.me).toEqual({ rank: 3, score: 500 })
    expect(board.total).toBe(3)
    expect(leaderboard(env.ctx, c, 'stapler').me).toEqual({ rank: 2, score: 500 })
    expect(leaderboard(env.ctx, d, 'stapler').me).toBeNull()
    expect(leaderboard(env.ctx, d, 'loren')).toMatchObject({ total: 1, me: { rank: 1, score: 10 } })
  })

  it('hides banned accounts (and brings them back when unbanned)', async () => {
    const env = makeEnv()
    const [a, b, c] = [await player(env, 'Alpha'), await player(env, 'Bravo'), await player(env, 'Charlie')]
    const t = env.clock.t
    putScore(env, 'stapler', a, 900, t)
    putScore(env, 'stapler', b, 800, t)
    putScore(env, 'stapler', c, 700, t)
    banUser(env.ctx, OWNER, a, 'cheating')
    const board = leaderboard(env.ctx, c, 'stapler')
    expect(board.entries.map((e) => e.name)).toEqual(['Bravo', 'Charlie'])
    expect(board.total).toBe(2)
    expect(board.me).toEqual({ rank: 2, score: 700 })
    unbanUser(env.ctx, OWNER, a)
    expect(leaderboard(env.ctx, c, 'stapler')).toMatchObject({ total: 3, me: { rank: 3 } })
  })

  it('shows only the top 100 but ranks everybody and counts the total', async () => {
    const env = makeEnv()
    const me = await player(env, 'Me')
    const t = env.clock.t
    for (let i = 0; i < 120; i++) {
      const uuid = (i + 1).toString(16).padStart(32, 'c')
      run(env.ctx.db, 'INSERT INTO users (uuid, name, name_lower, created_at, last_login_at) VALUES (?, ?, ?, ?, ?)', uuid, `P${i}`, `p${i}`, t, t)
      putScore(env, 'loren', uuid, 1000 - i, t)
    }
    putScore(env, 'loren', me, 1, t)
    const board = leaderboard(env.ctx, me, 'loren')
    expect(board.entries).toHaveLength(100)
    expect(board.total).toBe(121)
    expect(board.me).toEqual({ rank: 121, score: 1 })
  })

  it('ranks a submitted game among stored scores (tie keeps the older entry ahead)', async () => {
    const env = makeEnv()
    const a = await player(env, 'Alpha')
    const b = await player(env, 'Bravo')
    const v = shortest('stapler')
    expect(submitRun(env.ctx, a, 'stapler', prepared(env, a, v).body).rank).toBe(1)
    env.clock.advance(1000)
    expect(submitRun(env.ctx, b, 'stapler', prepared(env, b, v).body)).toMatchObject({ accepted: true, rank: 2, improved: true })
  })
})

describe('team removal', () => {
  it('needs minigames.manage, deletes the score and writes the audit log', async () => {
    const env = makeEnv()
    const a = await player(env, 'Alpha')
    putScore(env, 'stapler', a, 700, env.clock.t)
    expect(code(() => removeScore(env.ctx, NO_RIGHT, 'stapler', a, { reason: 'cheat' }))).toBe('missing_permission')
    expect(one(env.ctx.db, 'SELECT COUNT(*) AS n FROM minigame_scores')).toEqual({ n: 1 })
    expect(code(() => removeScore(env.ctx, WITH_RIGHT, 'stapler', a, { reason: '' }))).toBe('invalid_request')
    expect(code(() => removeScore(env.ctx, WITH_RIGHT, 'stapler', a, { reason: 'x'.repeat(301) }))).toBe('invalid_request')
    expect(code(() => removeScore(env.ctx, WITH_RIGHT, 'loren', a, { reason: 'cheat' }))).toBe('minigame_score_unknown')
    removeScore(env.ctx, WITH_RIGHT, 'stapler', a, { reason: 'Impossible score' })
    expect(one(env.ctx.db, 'SELECT COUNT(*) AS n FROM minigame_scores')).toEqual({ n: 0 })
    expect(one(env.ctx.db, "SELECT actor, target, detail FROM admin_log WHERE action = 'minigames.score.remove'"))
      .toEqual({ actor: WITH_RIGHT.uuid, target: a, detail: 'stapler (700): Impossible score' })
    // Owner darf immer.
    putScore(env, 'loren', a, 5, env.clock.t)
    removeScore(env.ctx, OWNER, 'loren', a, { reason: 'test' })
    expect(leaderboard(env.ctx, a, 'loren').total).toBe(0)
  })
})

describe('migration 40', () => {
  it('is idempotent and grants the right to owner and admin', () => {
    const env = makeEnv()
    migrateMinigames(env.ctx.db)
    migrateMinigames(env.ctx.db)
    const db = new DatabaseSync(':memory:')
    db.exec('CREATE TABLE users (uuid TEXT PRIMARY KEY)')
    migrateMinigames(db)
    migrateMinigames(db)
    for (const t of ['minigame_runs', 'minigame_scores']) {
      expect(db.prepare("SELECT 1 AS x FROM sqlite_master WHERE type = 'table' AND name = ?").get(t)).toBeTruthy()
    }
    // Rollen-Seeds: Recht fehlt in einer bestehenden Datenbank → Migration trägt es nach.
    for (const id of ['owner', 'admin']) {
      const row = one<{ permissions: string }>(env.ctx.db, 'SELECT permissions FROM team_roles WHERE id = ?', id)!
      run(env.ctx.db, 'UPDATE team_roles SET permissions = ? WHERE id = ?', JSON.stringify((JSON.parse(row.permissions) as string[]).filter((p) => p !== 'minigames.manage')), id)
    }
    migrateMinigames(env.ctx.db)
    migrateMinigames(env.ctx.db)
    for (const id of ['owner', 'admin']) {
      const perms = JSON.parse(one<{ permissions: string }>(env.ctx.db, 'SELECT permissions FROM team_roles WHERE id = ?', id)!.permissions) as string[]
      expect(perms.filter((p) => p === 'minigames.manage')).toHaveLength(1)
    }
    expect(one<{ permissions: string }>(env.ctx.db, "SELECT permissions FROM team_roles WHERE id = 'moderator'")!.permissions).not.toContain('minigames.manage')
  })

  it('runs after a gap in the numbering (no migration 39)', () => {
    const env = makeEnv()
    expect(one(env.ctx.db, 'SELECT version FROM schema_migrations WHERE version = 40')).toBeTruthy()
    expect(one(env.ctx.db, 'SELECT version FROM schema_migrations WHERE version = 39')).toBeUndefined()
  })
})
