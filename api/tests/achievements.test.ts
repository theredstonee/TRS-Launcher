import { DatabaseSync } from 'node:sqlite'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ACHIEVEMENTS, METRICS, REPORTED_METRICS } from '../server/lib/achievement-catalog'
import {
  achievementReportBody,
  checkAchievements,
  countAchievement,
  grantPendingRewards,
  myAchievements,
  playerAchievements,
  publicCatalog,
  reportAchievement,
  setAchievementsVisible,
  touchIssueAuthor,
  TOTAL_POINTS,
} from '../server/lib/achievements'
import { banUser } from '../server/lib/admin'
import { seedBuiltins } from '../server/lib/capes'
import { all, one, run } from '../server/lib/db'
import { block } from '../server/lib/friends'
import { parseWith } from '../server/lib/http'
import { adminUpdateIssue, createIssue, voteIssue } from '../server/lib/issues'
import { migrateAchievements } from '../server/lib/migrations'
import { reportPresence } from '../server/lib/playerevents'
import { ownerStaff } from '../server/lib/team'
import { deleteUser, getUser, type UserRow } from '../server/lib/users'
import catalogRoute from '../server/routes/v1/achievements/index.get'
import meRoute from '../server/routes/v1/me/achievements/index.get'
import reportRoute from '../server/routes/v1/me/achievements/report.post'
import settingsRoute from '../server/routes/v1/me/achievements/settings.patch'
import playerRoute from '../server/routes/v1/players/[uuid]/achievements.get'
import { befriend, code, players } from './chathelpers'
import { callRoute } from './circuithelpers'
import { ADMIN, login, makeEnv, solidPng, type TestEnv } from './helpers'

const MIN = 60_000
const DAY = 24 * 60 * MIN
const OWNER = ownerStaff(ADMIN)

afterEach(() => {
  vi.restoreAllMocks()
})

function events(env: TestEnv, uuid: string) {
  const got: { type: string, achievement?: { id: string, hidden: boolean }, reward?: unknown }[] = []
  env.ctx.events.subscribe(uuid, (e) => {
    if (e.type === 'achievement_unlocked') got.push(e as never)
  }, () => {}, 'me')
  return got
}

const unlockedIds = (env: TestEnv, uuid: string) =>
  all<{ achievement_id: string }>(env.ctx.db, 'SELECT achievement_id FROM achievement_unlocks WHERE uuid = ? ORDER BY achievement_id', uuid)
    .map((r) => r.achievement_id)

function beat(env: TestEnv, u: UserRow, state: 'online' | 'in-game' | 'offline', via?: 'client' | 'launcher') {
  reportPresence(env.ctx, getUser(env.ctx, u.uuid)!, {
    state,
    ...(via ? { via } : {}),
    ...(state === 'in-game' ? { game: { version: '1.21.1', loader: 'fabric' } } : {}),
  } as never)
}

/** Mitgelieferte Kosmetik direkt anlegen (Emote ohne Vorlage/Textur reicht für Belohnungen). */
function seedCosmeticRow(env: TestEnv, id: string, slot: 'emote' | 'hat' = 'emote') {
  if (slot === 'emote') {
    run(
      env.ctx.db,
      `INSERT INTO cosmetics (id, kind, slot, template, name, owner_uuid, status, unlock, frames, emissive, sort, retired, created_at)
       VALUES (?, 'builtin', 'emote', NULL, ?, NULL, 'approved', 'admin', 1, 0, 99, 0, ?)`,
      id, id, env.clock.t,
    )
  } else {
    run(
      env.ctx.db,
      `INSERT INTO cosmetics (id, kind, slot, template, name, owner_uuid, status, unlock, sha256, width, height, scale, frames, emissive, sort, retired, created_at)
       VALUES (?, 'builtin', 'hat', 'crown', ?, NULL, 'approved', 'code', 'x', 32, 32, 1, 1, 0, 99, 0, ?)`,
      id, id, env.clock.t,
    )
  }
}

function seedCape(env: TestEnv, id: string) {
  seedBuiltins(env.ctx, [{ id, name: id, unlock: 'admin', sort: 50, scale: 1, frames: 1, frameTimeMs: null, png: solidPng(64, 32) }])
}

describe('achievement catalog', () => {
  it('has 25–40 well-formed achievements with all three languages', () => {
    expect(ACHIEVEMENTS.length).toBeGreaterThanOrEqual(25)
    expect(ACHIEVEMENTS.length).toBeLessThanOrEqual(40)
    const ids = new Set<string>()
    for (const a of ACHIEVEMENTS) {
      expect(a.id).toMatch(/^[a-z0-9_]{1,40}$/)
      expect(ids.has(a.id)).toBe(false)
      ids.add(a.id)
      expect(METRICS).toContain(a.metric)
      for (const t of [a.title, a.description]) {
        for (const l of ['en', 'de', 'es'] as const) expect(t[l].trim().length).toBeGreaterThan(0)
      }
      if (a.secret) expect(a.category).toBe('secret')
      if (a.goal !== undefined) expect(a.unit).toBeDefined()
    }
  })

  it('rewards only on server-verified achievements, exactly the decided ones', () => {
    for (const a of ACHIEVEMENTS) if (a.reward) expect(REPORTED_METRICS.has(a.metric)).toBe(false)
    const rewards = Object.fromEntries(ACHIEVEMENTS.filter((a) => a.reward).map((a) => [a.id, a.reward]))
    expect(rewards).toEqual({
      play_100h: { kind: 'cape', id: 'veteran' },
      idea_implemented: { kind: 'cape', id: 'ideengeber' },
      friends_10: { kind: 'cosmetic', id: 'party' },
    })
  })

  it('the meta achievement counts every secret achievement', () => {
    const meta = ACHIEVEMENTS.find((a) => a.id === 'all_secrets')!
    expect(meta.goal).toBe(ACHIEVEMENTS.filter((a) => a.secret).length)
    expect(meta.secret).toBeUndefined()
  })

  it('public catalog hides secret texts and rewards', () => {
    const { achievements } = publicCatalog()
    expect(achievements.map((a) => a.order)).toEqual(achievements.map((_, i) => i))
    for (const a of achievements) {
      if (a.secret) {
        expect(a).toMatchObject({ hidden: true, title: null, description: null, reward: null, goal: null, unit: null, icon: 'secret' })
        expect(JSON.stringify(a)).not.toMatch(/duck|Quack|owl/i)
      } else {
        expect(a.hidden).toBe(false)
        expect(a.title?.en).toBeTruthy()
      }
    }
    expect(achievements.find((a) => a.id === 'play_100h')).toMatchObject({ verified: true, goal: 6000, unit: 'minutes', reward: { kind: 'cape', id: 'veteran' } })
    expect(achievements.find((a) => a.id === 'first_launch')).toMatchObject({ verified: false, goal: null, unit: null })
  })

  it('migration 19 is idempotent', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('CREATE TABLE users (uuid TEXT PRIMARY KEY)')
    migrateAchievements(db)
    migrateAchievements(db)
    const tables = (db.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'achievement_%' ORDER BY name").all() as { name: string }[]).map((r) => r.name)
    expect(tables).toEqual(['achievement_playtime', 'achievement_stats', 'achievement_unlocks'])
    const cols = (db.prepare('PRAGMA table_info(users)').all() as { name: string, dflt_value: string }[])
    expect(cols.find((c) => c.name === 'achievements_visible')?.dflt_value).toBe('1')
  })
})

describe('launcher reports (§31.4)', () => {
  it('validates the body strictly', () => {
    const ok = (v: unknown) => code(() => parseWith(achievementReportBody, v))
    expect(ok({ kind: 'launch' })).toBe('ok')
    expect(ok({ kind: 'launch', hour: 0 })).toBe('ok')
    expect(ok({ kind: 'launch', hour: 23 })).toBe('ok')
    expect(ok({ kind: 'mod_installed', count: 100 })).toBe('ok')
    expect(ok({ kind: 'launch', hour: 24 })).toBe('invalid_request')
    expect(ok({ kind: 'launch', hour: 2.5 })).toBe('invalid_request')
    expect(ok({ kind: 'mod_installed', hour: 3 })).toBe('invalid_request')
    expect(ok({ kind: 'launch', count: 2 })).toBe('invalid_request')
    expect(ok({ kind: 'mod_installed', count: 101 })).toBe('invalid_request')
    expect(ok({ kind: 'mod_installed', count: 0 })).toBe('invalid_request')
    expect(ok({ kind: 'nope' })).toBe('invalid_request')
    expect(ok({ kind: 'launch', extra: 1 })).toBe('invalid_request')
    expect(ok(undefined)).toBe('invalid_request')
  })

  it('launch unlocks once, sends one event; the night hour unlocks the secret', async () => {
    const env = makeEnv()
    const [a] = await players(env, 'Alex')
    const ev = events(env, a!.uuid)
    expect(reportAchievement(env.ctx, a!.uuid, { kind: 'launch', hour: 14 }).newlyUnlocked.map((u) => u.id)).toEqual(['first_launch'])
    expect(reportAchievement(env.ctx, a!.uuid, { kind: 'launch' }).newlyUnlocked).toEqual([])
    expect(ev.map((e) => e.achievement!.id)).toEqual(['first_launch'])
    expect(ev[0]).toMatchObject({ reward: null, achievement: { hidden: false } })

    expect(reportAchievement(env.ctx, a!.uuid, { kind: 'launch', hour: 3 }).newlyUnlocked.map((u) => u.id)).toEqual(['secret_02'])
    // Geheimer Erfolg kommt mit Text im Ereignis.
    expect(ev.at(-1)).toMatchObject({ achievement: { id: 'secret_02', hidden: false, title: { en: 'Night owl' } } })
  })

  it('counters stop at the highest goal; count adds up', async () => {
    const env = makeEnv()
    const [a] = await players(env, 'Alex')
    expect(reportAchievement(env.ctx, a!.uuid, { kind: 'mod_installed', count: 30 }).newlyUnlocked.map((u) => u.id)).toEqual(['first_mod'])
    expect(reportAchievement(env.ctx, a!.uuid, { kind: 'mod_installed', count: 30 }).newlyUnlocked.map((u) => u.id)).toEqual(['mods_50'])
    for (let i = 0; i < 3; i++) reportAchievement(env.ctx, a!.uuid, { kind: 'mod_installed', count: 100 })
    expect(one<{ value: number }>(env.ctx.db, "SELECT value FROM achievement_stats WHERE uuid = ? AND key = 'mods_installed'", a!.uuid)!.value).toBe(50)
    reportAchievement(env.ctx, a!.uuid, { kind: 'crash_fixed' })
    reportAchievement(env.ctx, a!.uuid, { kind: 'crash_fixed' })
    expect(one<{ value: number }>(env.ctx.db, "SELECT value FROM achievement_stats WHERE uuid = ? AND key = 'crash_fixed_flag'", a!.uuid)!.value).toBe(1)
    expect(unlockedIds(env, a!.uuid)).toEqual(['crash_fixed', 'first_mod', 'mods_50'])
  })
})

describe('playtime and streak from heartbeats (§31.7)', () => {
  it('counts in-game wall time, ignores long gaps and online time, never counts two sources twice', async () => {
    const env = makeEnv()
    const [a] = await players(env, 'Alex')
    const played = () => one<{ played_ms: number }>(env.ctx.db, 'SELECT played_ms FROM achievement_playtime WHERE uuid = ?', a!.uuid)!.played_ms

    beat(env, a!, 'online', 'launcher')
    env.clock.advance(MIN)
    beat(env, a!, 'online', 'launcher')
    expect(played()).toBe(0)

    // Mod und Launcher melden beide in-game, versetzt um 30 s → zusammen genau die Wanduhr-Zeit.
    beat(env, a!, 'in-game', 'client')
    for (let i = 0; i < 10; i++) {
      env.clock.advance(30_000)
      beat(env, a!, 'in-game', 'launcher')
      env.clock.advance(30_000)
      beat(env, a!, 'in-game', 'client')
    }
    expect(played()).toBe(10 * MIN)

    // Lücke über der Ablaufzeit (180 s) → keine Gutschrift, neue Sitzung.
    env.clock.advance(10 * MIN)
    beat(env, a!, 'in-game', 'client')
    expect(played()).toBe(10 * MIN)
    env.clock.advance(MIN)
    beat(env, a!, 'in-game', 'client')
    expect(played()).toBe(11 * MIN)

    // Spiel vorbei (Launcher wieder online, Mod offline) → danach zählt nichts.
    beat(env, a!, 'offline', 'client')
    beat(env, a!, 'online', 'launcher')
    env.clock.advance(MIN)
    beat(env, a!, 'online', 'launcher')
    expect(played()).toBe(11 * MIN)
  })

  it('unlocks play_1h after an hour in game and the marathon secret after 6 hours in one session', async () => {
    const env = makeEnv()
    const [a] = await players(env, 'Alex')
    const ev = events(env, a!.uuid)
    beat(env, a!, 'in-game', 'client')
    for (let i = 0; i < 60; i++) {
      env.clock.advance(MIN)
      beat(env, a!, 'in-game', 'client')
    }
    expect(ev.map((e) => e.achievement!.id)).toEqual(['play_1h'])
    for (let i = 0; i < 300; i++) {
      env.clock.advance(MIN)
      beat(env, a!, 'in-game', 'client')
    }
    expect(unlockedIds(env, a!.uuid)).toEqual(['play_1h', 'secret_05'])
    const me = myAchievements(env.ctx, a!.uuid)
    expect(me.progress.play_10h).toBe(360)
    expect(me.progress.play_1h).toBe(60)
    expect(me.progress.secret_05).toBe(360)
  })

  it('a 7-day streak needs consecutive UTC days; a missed day starts over', async () => {
    const env = makeEnv()
    const [a] = await players(env, 'Alex')
    for (let d = 0; d < 3; d++) {
      beat(env, a!, 'online', 'launcher')
      env.clock.advance(DAY)
    }
    env.clock.advance(DAY) // Tag ausgelassen
    for (let d = 0; d < 6; d++) {
      beat(env, a!, 'online', 'launcher')
      beat(env, a!, 'online', 'launcher')
      env.clock.advance(DAY)
    }
    expect(unlockedIds(env, a!.uuid)).not.toContain('streak_7')
    expect(myAchievements(env.ctx, a!.uuid).progress.streak_7).toBe(6)
    beat(env, a!, 'online', 'launcher')
    expect(unlockedIds(env, a!.uuid)).toContain('streak_7')
    // offline zählt nicht als Aktivität
    const row = one<{ streak: number }>(env.ctx.db, 'SELECT streak FROM achievement_playtime WHERE uuid = ?', a!.uuid)!
    env.clock.advance(DAY)
    beat(env, a!, 'offline', 'launcher')
    expect(one<{ streak: number }>(env.ctx.db, 'SELECT streak FROM achievement_playtime WHERE uuid = ?', a!.uuid)!.streak).toBe(row.streak)
  })
})

describe('server-verified community achievements', () => {
  it('friends: both sides unlock; 10 friends grant the party emote', async () => {
    const env = makeEnv()
    const all11 = await players(env, 'Alex', 'B1', 'B2', 'B3', 'B4', 'B5', 'B6', 'B7', 'B8', 'B9', 'B10')
    const [a, ...others] = all11
    const ev = events(env, a!.uuid)
    befriend(env, a!, others[0]!)
    expect(unlockedIds(env, a!.uuid)).toEqual(['first_friend'])
    expect(unlockedIds(env, others[0]!.uuid)).toEqual(['first_friend'])
    for (const o of others.slice(1)) befriend(env, a!, o)
    expect(unlockedIds(env, a!.uuid)).toEqual(['first_friend', 'friends_10'])
    const tenth = ev.find((e) => e.achievement!.id === 'friends_10')!
    expect(tenth.reward).toEqual({ kind: 'cosmetic', id: 'party' })
    expect(one(env.ctx.db, "SELECT source FROM user_cosmetics WHERE uuid = ? AND cosmetic_id = 'party'", a!.uuid)).toEqual({ source: 'admin' })
    // Noch einmal prüfen → keine zweite Freischaltung, nichts nachzureichen.
    expect(grantPendingRewards(env.ctx)).toBe(0)
    expect(checkAchievements(env.ctx, a!.uuid)).toEqual([])
  })

  it('a reward whose item does not exist yet is granted later (logged once while missing)', async () => {
    const env = makeEnv()
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const [a] = await players(env, 'Alex')
    // Umhang „ideengeber“ fehlt noch (nicht angelegt).
    const issue = createIssue(env.ctx, a!, { type: 'feature', area: 'launcher', title: 'Add achievements please', description: 'Would be fun.' })
    adminUpdateIssue(env.ctx, OWNER, issue.number, { status: 'done' })
    touchIssueAuthor(env.ctx, issue.number)
    expect(unlockedIds(env, a!.uuid)).toContain('idea_implemented')
    expect(warn.mock.calls.filter((c) => String(c[0]).includes('cape:ideengeber'))).toHaveLength(1)
    expect(grantPendingRewards(env.ctx)).toBe(0)
    expect(warn.mock.calls.filter((c) => String(c[0]).includes('cape:ideengeber'))).toHaveLength(1)
    seedCape(env, 'ideengeber')
    expect(grantPendingRewards(env.ctx)).toBe(1)
    expect(grantPendingRewards(env.ctx)).toBe(0)
    expect(one(env.ctx.db, "SELECT 1 AS x FROM user_capes WHERE uuid = ? AND cape_id = 'ideengeber'", a!.uuid)).toBeDefined()
  })

  it('an implemented idea grants the cape immediately when it exists; up-votes from others count', async () => {
    const env = makeEnv()
    seedCape(env, 'ideengeber')
    const [a, b, c] = await players(env, 'Alex', 'Bea', 'Carl')
    const ev = events(env, a!.uuid)
    const issue = createIssue(env.ctx, a!, { type: 'feature', area: 'launcher', title: 'Add achievements please', description: 'Would be fun.' })
    expect(checkAchievements(env.ctx, a!.uuid, ['issues_opened', 'bug_from_game_flag']).map((u) => u.id)).toEqual(['first_issue'])

    voteIssue(env.ctx, { uuid: a!.uuid, name: a!.name, staff: null }, issue.number, 1)
    voteIssue(env.ctx, { uuid: b!.uuid, name: b!.name, staff: null }, issue.number, 1)
    voteIssue(env.ctx, { uuid: c!.uuid, name: c!.name, staff: null }, issue.number, -1)
    touchIssueAuthor(env.ctx, issue.number)
    expect(myAchievements(env.ctx, a!.uuid).progress.upvotes_10).toBe(1)

    adminUpdateIssue(env.ctx, OWNER, issue.number, { status: 'planned' })
    touchIssueAuthor(env.ctx, issue.number)
    expect(unlockedIds(env, a!.uuid)).not.toContain('idea_implemented')
    adminUpdateIssue(env.ctx, OWNER, issue.number, { status: 'done' })
    touchIssueAuthor(env.ctx, issue.number)
    const e = ev.find((x) => x.achievement!.id === 'idea_implemented')!
    expect(e.reward).toEqual({ kind: 'cape', id: 'ideengeber' })
    expect(one(env.ctx.db, "SELECT 1 AS x FROM user_capes WHERE uuid = ? AND cape_id = 'ideengeber'", a!.uuid)).toBeDefined()
    expect(unlockedIds(env, a!.uuid)).not.toContain('bug_squashed')
  })

  it('a bug report from inside the game unlocks the secret; stored counters keep expired things', async () => {
    const env = makeEnv()
    const [a] = await players(env, 'Alex')
    createIssue(env.ctx, a!, { type: 'bug', area: 'client', title: 'Minimap flickers at night', description: 'Flickers.', meta: { modVersion: '0.13.0', mcVersion: '1.21.1', loader: 'fabric' } })
    expect(checkAchievements(env.ctx, a!.uuid, ['issues_opened', 'bug_from_game_flag']).map((u) => u.id).sort()).toEqual(['first_issue', 'secret_03'])

    countAchievement(env.ctx, a!.uuid, 'worlds_hosted')
    countAchievement(env.ctx, a!.uuid, 'screenshots_shared')
    expect(unlockedIds(env, a!.uuid)).toEqual(['first_issue', 'first_world_hosted', 'screenshot_shared', 'secret_03'])
    for (let i = 0; i < 9; i++) countAchievement(env.ctx, a!.uuid, 'pack_installs')
    expect(unlockedIds(env, a!.uuid)).not.toContain('pack_installs_10')
    countAchievement(env.ctx, a!.uuid, 'pack_installs')
    expect(unlockedIds(env, a!.uuid)).toContain('pack_installs_10')
  })

  it('all secrets unlock the meta achievement (no reward)', async () => {
    const env = makeEnv()
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    const [a] = await players(env, 'Alex')
    // 01: Quietscheente besitzen
    seedCosmeticRow(env, 'rubber_duck', 'hat')
    run(env.ctx.db, "INSERT INTO user_cosmetics (uuid, cosmetic_id, source, granted_at) VALUES (?, 'rubber_duck', 'code', ?)", a!.uuid, env.clock.t)
    // 02: Nacht-Start
    reportAchievement(env.ctx, a!.uuid, { kind: 'launch', hour: 3 })
    // 03: Fehler aus dem Spiel
    createIssue(env.ctx, a!, { type: 'bug', area: 'client', title: 'Minimap flickers at night', description: 'Flickers.', meta: { modVersion: '0.13.0' } })
    // 04: Code eingelöst
    run(env.ctx.db, "INSERT INTO codes (code_hash, hint, cosmetic_id, max_uses, created_at, created_by) VALUES ('h2', 'AB', 'rubber_duck', 1, 0, 'x')")
    const codeId = one<{ id: number }>(env.ctx.db, "SELECT id FROM codes WHERE code_hash = 'h2'")!.id
    run(env.ctx.db, 'INSERT INTO code_redemptions (code_id, uuid, redeemed_at) VALUES (?, ?, ?)', codeId, a!.uuid, env.clock.t)
    checkAchievements(env.ctx, a!.uuid)
    expect(unlockedIds(env, a!.uuid)).not.toContain('all_secrets')
    expect(myAchievements(env.ctx, a!.uuid).progress.all_secrets).toBe(4)
    // 05: 6 h am Stück
    beat(env, a!, 'in-game', 'client')
    for (let i = 0; i < 360; i++) {
      env.clock.advance(MIN)
      beat(env, a!, 'in-game', 'client')
    }
    expect(unlockedIds(env, a!.uuid)).toEqual(expect.arrayContaining(['secret_01', 'secret_02', 'secret_03', 'secret_04', 'secret_05', 'all_secrets']))
    const me = myAchievements(env.ctx, a!.uuid)
    expect(me.achievements.find((x) => x.id === 'all_secrets')!.reward).toBeNull()
    expect(me.achievements.filter((x) => x.secret).every((x) => !x.hidden && x.title !== null)).toBe(true)
    expect(me.progress.all_secrets).toBe(5)
  })
})

describe('GET /v1/me/achievements and friends visibility', () => {
  it('shows own view: hidden secrets, progress, points', async () => {
    const env = makeEnv()
    const [a] = await players(env, 'Alex')
    reportAchievement(env.ctx, a!.uuid, { kind: 'launch' })
    env.clock.advance(1000)
    reportAchievement(env.ctx, a!.uuid, { kind: 'clip_recorded', count: 3 })
    const me = myAchievements(env.ctx, a!.uuid)
    expect(me.unlocked.map((u) => u.id)).toEqual(['first_launch', 'first_clip'])
    expect(me.points).toBe(15)
    expect(me.totalPoints).toBe(TOTAL_POINTS)
    expect(me.progress).toMatchObject({ launches_50: 1, clips_25: 3, play_1h: 0, friends_10: 0 })
    expect(me.progress.secret_05).toBeUndefined()
    expect(me.progress.first_launch).toBeUndefined()
    expect(me.achievements.find((x) => x.id === 'secret_01')).toMatchObject({ hidden: true, title: null })
  })

  it('only yourself and accepted friends; blocked, banned, strangers get 404', async () => {
    const env = makeEnv()
    const [a, b, c, d] = await players(env, 'Alex', 'Bea', 'Carl', 'Dora')
    reportAchievement(env.ctx, b!.uuid, { kind: 'launch' })
    expect(playerAchievements(env.ctx, a!.uuid, a!.uuid)).toEqual({ hidden: false, unlocked: [], points: 0 })
    expect(code(() => playerAchievements(env.ctx, a!.uuid, b!.uuid))).toBe('player_not_found')
    expect(code(() => playerAchievements(env.ctx, a!.uuid, 'f'.repeat(32)))).toBe('player_not_found')
    env.clock.advance(1000)
    befriend(env, a!, b!)
    const view = playerAchievements(env.ctx, a!.uuid, b!.uuid)
    expect(view.unlocked.map((u) => u.id)).toEqual(['first_launch', 'first_friend'])
    expect(view.points).toBe(15)
    expect(Object.keys(view).sort()).toEqual(['hidden', 'points', 'unlocked'])
    expect(view.hidden).toBe(false)

    // Bea verbirgt ihre Erfolge: Freunde sehen nur `hidden`, sie selbst alles; blockiert bleibt 404.
    expect(myAchievements(env.ctx, b!.uuid).visibleToFriends).toBe(true)
    expect(setAchievementsVisible(env.ctx, b!.uuid, false)).toEqual({ visibleToFriends: false })
    expect(myAchievements(env.ctx, b!.uuid).visibleToFriends).toBe(false)
    expect(playerAchievements(env.ctx, a!.uuid, b!.uuid)).toEqual({ hidden: true, unlocked: [], points: 0 })
    expect(playerAchievements(env.ctx, b!.uuid, b!.uuid)).toMatchObject({ hidden: false, points: 15 })
    expect(code(() => playerAchievements(env.ctx, c!.uuid, b!.uuid))).toBe('player_not_found')
    setAchievementsVisible(env.ctx, b!.uuid, true)
    expect(playerAchievements(env.ctx, a!.uuid, b!.uuid).points).toBe(15)

    befriend(env, a!, c!)
    block(env.ctx, c!.uuid, { uuid: a!.uuid })
    expect(code(() => playerAchievements(env.ctx, a!.uuid, c!.uuid))).toBe('player_not_found')

    befriend(env, a!, d!)
    expect(code(() => playerAchievements(env.ctx, a!.uuid, d!.uuid))).toBe('ok')
    banUser(env.ctx, 'api-key', d!.uuid, undefined)
    expect(code(() => playerAchievements(env.ctx, a!.uuid, d!.uuid))).toBe('player_not_found')
  })

  it('routes: public catalog, report with auth + validation + rate limit, own view, friend view', async () => {
    const env = makeEnv()
    const alex = await login(env, 'Alex')
    const bea = await login(env, 'Bea')
    const auth = (t: string) => ({ authorization: `Bearer ${t}`, 'content-type': 'application/json' })

    const cat = await callRoute(env, catalogRoute, { url: '/v1/achievements' })
    expect((cat.body as { achievements: unknown[] }).achievements).toHaveLength(ACHIEVEMENTS.length)

    const post = (t: string, body: unknown) => callRoute(env, reportRoute, { method: 'POST', url: '/v1/me/achievements/report', headers: auth(t), body: JSON.stringify(body) })
    expect((await callRoute(env, reportRoute, { method: 'POST', url: '/v1/me/achievements/report', body: '{"kind":"launch"}', headers: { 'content-type': 'application/json' } })).error?.code).toBe('unauthorized')
    expect((await post(alex.token, { kind: 'launch', hour: 3 })).body).toMatchObject({ newlyUnlocked: [{ id: 'first_launch' }, { id: 'secret_02' }] })
    expect((await post(alex.token, { kind: 'modpack_installed', hour: 3 })).error?.code).toBe('invalid_request')
    let limited = 0
    for (let i = 0; i < 40; i++) if ((await post(alex.token, { kind: 'launch' })).error?.code === 'rate_limited') limited++
    expect(limited).toBeGreaterThan(0)

    const me = await callRoute(env, meRoute, { url: '/v1/me/achievements', headers: auth(alex.token) })
    expect((me.body as { unlocked: { id: string }[] }).unlocked.map((u) => u.id).sort()).toEqual(['first_launch', 'secret_02'])

    const friend = (t: string, uuid: string) => callRoute(env, playerRoute, { url: `/v1/players/${uuid}/achievements`, params: { uuid }, headers: auth(t) })
    expect((await friend(bea.token, alex.user.uuid)).error?.code).toBe('player_not_found')
    befriend(env, getUser(env.ctx, alex.user.uuid)!, getUser(env.ctx, bea.user.uuid)!)
    expect((await friend(bea.token, alex.user.uuid)).body).toMatchObject({ hidden: false, points: 30 })
    expect((await friend(bea.token, 'not-a-uuid')).error?.code).toBe('not_found')

    const patch = (t: string, body: string) => callRoute(env, settingsRoute, { method: 'PATCH', url: '/v1/me/achievements/settings', headers: auth(t), body })
    expect((await patch(alex.token, '{"visibleToFriends":"no"}')).error?.code).toBe('invalid_request')
    expect((await patch(alex.token, '{"visibleToFriends":false,"x":1}')).error?.code).toBe('invalid_request')
    expect((await patch(alex.token, '{}')).error?.code).toBe('invalid_request')
    expect((await patch(alex.token, '{"visibleToFriends":false}')).body).toEqual({ visibleToFriends: false })
    expect((await friend(bea.token, alex.user.uuid)).body).toEqual({ hidden: true, unlocked: [], points: 0 })
    expect((await callRoute(env, meRoute, { url: '/v1/me/achievements', headers: auth(alex.token) })).body).toMatchObject({ visibleToFriends: false, points: 30 })
  })

  it('account deletion removes unlocks, counters and playtime', async () => {
    const env = makeEnv()
    const [a] = await players(env, 'Alex')
    reportAchievement(env.ctx, a!.uuid, { kind: 'launch' })
    beat(env, a!, 'in-game', 'client')
    deleteUser(env.ctx, a!.uuid)
    for (const t of ['achievement_unlocks', 'achievement_stats', 'achievement_playtime']) {
      expect(one<{ n: number }>(env.ctx.db, `SELECT COUNT(*) AS n FROM ${t} WHERE uuid = ?`, a!.uuid)!.n).toBe(0)
    }
  })
})
