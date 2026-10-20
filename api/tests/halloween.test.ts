import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { DatabaseSync } from 'node:sqlite'
import { PNG } from 'pngjs'
import { loadBuiltins } from '../server/lib/builtin'
import { buildV2Cosmetic } from '../server/lib/cosmetics-v2'
import wingsRoute from '../server/routes/v1/me/halloween/wings.post'
import { describe, expect, it } from 'vitest'
import { HALLOWEEN_CAPES, HALLOWEEN_WING_IDS, HUNTER_CAPE, PUMPKINS, STICKERS, TRICK_POOL } from '../shared/halloween'
import { catalog, claimCape, grantCape, seedBuiltins, type BuiltinCape } from '../server/lib/capes'
import { editMessage, openDm, sendMessage } from '../server/lib/chat'
import { createCodes } from '../server/lib/codes'
import { cosmeticCatalog, equipCosmetics, grantCosmetic, ownedEmotes, seedBuiltinCosmetics } from '../server/lib/cosmetics'
import { all, migrate, one, run } from '../server/lib/db'
import {
  berlinDay,
  ensureEventBadge,
  findPumpkin,
  grantPendingHalloweenRewards,
  grantSticker,
  halloweenView,
  nextBerlinMidnight,
  ownedStickers,
  setEffects,
  trickOrTreat,
  unownedPool,
} from '../server/lib/halloween'
import { addEventPlayer, setEventEnabled } from '../server/lib/liveevents'
import { lookupPlayers } from '../server/lib/lookup'
import { MIGRATIONS, migrateHalloween2026 } from '../server/lib/migrations'
import { publicCapes } from '../server/lib/site'
import { ownerStaff } from '../server/lib/team'
import { updateSettings } from '../server/lib/users'
import meRoute from '../server/routes/v1/me/index.get'
import cosmeticsRoute from '../server/routes/v1/me/cosmetics.get'
import halloweenRoute from '../server/routes/v1/me/halloween/index.get'
import pumpkinRoute from '../server/routes/v1/me/halloween/pumpkins/[id].post'
import trickRoute from '../server/routes/v1/me/halloween/trick.post'
import effectsRoute from '../server/routes/v1/me/halloween/effects.put'
import { befriend, code, listen, players } from './chathelpers'
import { callRoute } from './circuithelpers'
import { ADMIN, login, makeEnv, solidPng, type TestEnv } from './helpers'

const OWNER = ownerStaff(ADMIN)
/** Mitten im Event (Mi., 21.10.2026 14:00 Berlin = 12:00 UTC). */
const IN_EVENT = Date.UTC(2026, 9, 21, 12, 0, 0)
const auth = (token: string) => ({ authorization: `Bearer ${token}`, 'content-type': 'application/json' })

function rewardCapes(): BuiltinCape[] {
  return [...HALLOWEEN_CAPES, HUNTER_CAPE].map((c, i) => ({
    id: c.id, name: c.name.de, unlock: 'admin' as const, reward: 'halloween2026', sort: i, scale: 2, frames: 1, frameTimeMs: null,
    png: solidPng(128, 64, [40, 20, 60, 255]),
  }))
}

/** Event an (global), Uhr mitten ins Event, Belohnungs-Umhänge eingespielt. */
function eventEnv(on = true): TestEnv {
  const env = makeEnv()
  env.clock.t = IN_EVENT
  seedBuiltins(env.ctx, rewardCapes())
  if (on) setEventEnabled(env.ctx, ADMIN, 'halloween', true)
  return env
}

const badgesOf = (env: TestEnv, uuid: string) =>
  all<{ badge: string }>(env.ctx.db, 'SELECT badge FROM user_badges WHERE uuid = ? ORDER BY badge', uuid).map((r) => r.badge)

describe('migration 26: halloween 2026', () => {
  it('creates the tables, seeds 6 stickers, adds reward columns, accepts source reward, is idempotent', () => {
    const env = makeEnv()
    const db = env.ctx.db
    for (const t of ['user_badges', 'halloween_pumpkins', 'halloween_tricks', 'halloween_effects', 'stickers', 'user_stickers']) {
      expect(one(db, "SELECT 1 AS x FROM sqlite_master WHERE type = 'table' AND name = ?", t)).toBeDefined()
    }
    expect(all<{ id: string }>(db, 'SELECT id FROM stickers ORDER BY sort').map((r) => r.id)).toEqual(STICKERS.map((s) => s.id))
    migrateHalloween2026(db)
    migrateHalloween2026(db)
    expect(all(db, 'SELECT id FROM stickers')).toHaveLength(6)
    const sql = (n: string) => one<{ sql: string }>(db, "SELECT sql FROM sqlite_master WHERE name = ?", n)!.sql
    expect(sql('user_capes')).toContain("'reward'")
    expect(sql('user_cosmetics')).toContain("'reward'")
    expect(MIGRATIONS.at(-1)!.version).toBeGreaterThanOrEqual(27)
  })

  it('keeps existing ownership when the leaf tables are rebuilt', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('PRAGMA foreign_keys = ON')
    const upTo25 = MIGRATIONS.filter((m) => m.version <= 25)
    db.exec('CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)')
    for (const m of upTo25) {
      if (m.sql) db.exec(m.sql)
      m.run?.(db)
      run(db, 'INSERT INTO schema_migrations (version, applied_at) VALUES (?, 0)', m.version)
    }
    run(db, "INSERT INTO users (uuid, name, name_lower, created_at, last_login_at) VALUES (?, 'A', 'a', 0, 0)", 'a'.repeat(32))
    run(db, "INSERT INTO capes (id, kind, name, status, unlock, sha256, width, height, created_at) VALUES ('x', 'builtin', 'X', 'approved', 'code', 'h', 64, 32, 0)")
    run(db, "INSERT INTO user_capes (uuid, cape_id, source, granted_at) VALUES (?, 'x', 'creator', 1)", 'a'.repeat(32))
    // Migrationen ab 26 (Halloween, user_event_flags, empfohlene Modpacks …) laufen hier alle nach.
    expect(migrate(db)).toBe(MIGRATIONS.filter((m) => m.version > 25).length)
    expect(one<{ source: string }>(db, 'SELECT source FROM user_capes')!.source).toBe('creator')
  })
})

describe('halloween badge window', () => {
  it('grants halloween2026 on GET /v1/me only while the event is active and before the end', async () => {
    const env = eventEnv(false)
    const p = await login(env, 'Pixel')
    await callRoute(env, meRoute, { url: '/v1/me', headers: auth(p.token) })
    expect(badgesOf(env, p.user.uuid)).toEqual([])

    setEventEnabled(env.ctx, ADMIN, 'halloween', true)
    const l = listen(env, p.user.uuid)
    const r = await callRoute(env, meRoute, { url: '/v1/me', headers: auth(p.token) })
    expect(r.status).toBe(200)
    expect(badgesOf(env, p.user.uuid)).toEqual(['halloween2026'])
    expect(l.of('halloween_updated')).toHaveLength(1)
    expect(l.of('halloween_updated')[0]!.badges).toEqual(['halloween2026'])
    // Zweiter Aufruf: nichts Neues.
    await callRoute(env, meRoute, { url: '/v1/me', headers: auth(p.token) })
    expect(l.of('halloween_updated')).toHaveLength(1)

    // Nach dem Ende (3.11. 00:00 Berlin): nie mehr vergeben, aber behalten.
    const late = await login(env, 'Late')
    env.clock.t = Date.UTC(2026, 10, 2, 23, 0, 0)
    expect(ensureEventBadge(env.ctx, late.user.uuid)).toBe(false)
    expect(badgesOf(env, late.user.uuid)).toEqual([])
    expect(badgesOf(env, p.user.uuid)).toEqual(['halloween2026'])
    env.clock.t = Date.UTC(2026, 10, 2, 22, 59, 59)
    expect(ensureEventBadge(env.ctx, late.user.uuid)).toBe(true)
  })

  it('counts single-player releases while the event is off globally', async () => {
    const env = eventEnv(false)
    const p = await login(env, 'Tester')
    addEventPlayer(env.ctx, ADMIN, 'halloween', p.user.uuid, 'Tester')
    expect(ensureEventBadge(env.ctx, p.user.uuid)).toBe(true)
    expect(halloweenView(env.ctx, p.user.uuid).active).toBe(true)
  })
})

describe('pumpkin hunt', () => {
  it('rejects unknown ids and inactive events, is idempotent and gives the next riddle', async () => {
    const env = eventEnv(false)
    const p = await login(env, 'Hunter')
    expect(code(() => findPumpkin(env.ctx, p.user.uuid, 'web-nope'))).toBe('pumpkin_unknown')
    expect(code(() => findPumpkin(env.ctx, p.user.uuid, 'web-home'))).toBe('event_inactive')
    setEventEnabled(env.ctx, ADMIN, 'halloween', true)

    const v0 = halloweenView(env.ctx, p.user.uuid)
    expect(v0.pumpkins).toMatchObject({ total: 13, found: [] })
    expect(v0.pumpkins.nextHint!.en).toBe('Where windows close, the spider weaves its web.')

    const r1 = findPumpkin(env.ctx, p.user.uuid, 'launcher-titlebar')
    expect(r1).toEqual({ found: ['launcher-titlebar'], count: 1, complete: false })
    expect(findPumpkin(env.ctx, p.user.uuid, 'launcher-titlebar').count).toBe(1)
    expect(halloweenView(env.ctx, p.user.uuid).pumpkins.nextHint!.de).toBe('Ganz unten, wo die Nummer der Version wohnt.')

    // Route: unbekannt → 404, 1 je 2 s.
    const bad = await callRoute(env, pumpkinRoute, { method: 'POST', url: '/v1/me/halloween/pumpkins/x', params: { id: 'x' }, headers: auth(p.token) })
    expect(bad.status).toBe(404)
    expect(bad.error!.code).toBe('pumpkin_unknown')
    const ok = await callRoute(env, pumpkinRoute, { method: 'POST', url: '/v1/me/halloween/pumpkins/web-home', params: { id: 'web-home' }, headers: auth(p.token) })
    expect(ok.status).toBe(429)
    env.clock.advance(2100)
    const ok2 = await callRoute(env, pumpkinRoute, { method: 'POST', url: '/v1/me/halloween/pumpkins/web-home', params: { id: 'web-home' }, headers: auth(p.token) })
    expect(ok2.status).toBe(200)
    expect((ok2.body as { count: number }).count).toBe(2)
  })

  it('13 finds give the hunter badge and cape exactly once', async () => {
    const env = eventEnv()
    const p = await login(env, 'Seeker')
    const l = listen(env, p.user.uuid)
    let last
    for (const id of PUMPKINS) last = findPumpkin(env.ctx, p.user.uuid, id)
    expect(last).toEqual({ found: [...PUMPKINS], count: 13, complete: true, reward: { badge: 'pumpkinhunter2026', cape: 'halloween_hunter_2026' } })
    expect(badgesOf(env, p.user.uuid)).toContain('pumpkinhunter2026')
    expect(one(env.ctx.db, "SELECT source FROM user_capes WHERE uuid = ? AND cape_id = 'halloween_hunter_2026'", p.user.uuid)).toEqual({ source: 'reward' })
    expect(halloweenView(env.ctx, p.user.uuid).pumpkins.nextHint).toBeNull()
    expect(l.of('halloween_updated')).toHaveLength(13)
    // Noch einmal: keine Belohnung, kein Ereignis.
    expect(findPumpkin(env.ctx, p.user.uuid, 'web-home')).toEqual({ found: [...PUMPKINS], count: 13, complete: true })
    expect(l.of('halloween_updated')).toHaveLength(13)
    expect(catalog(env.ctx, p.user.uuid).find((c) => c.id === HUNTER_CAPE.id)).toMatchObject({ owned: true, unlock: 'reward', rewardOf: 'halloween2026' })
  })

  it('hands out a missing hunter cape on the next start', async () => {
    const env = makeEnv()
    env.clock.t = IN_EVENT
    setEventEnabled(env.ctx, ADMIN, 'halloween', true)
    const p = await login(env, 'Early')
    for (const id of PUMPKINS) findPumpkin(env.ctx, p.user.uuid, id)
    expect(one(env.ctx.db, 'SELECT 1 AS x FROM user_capes WHERE uuid = ?', p.user.uuid)).toBeUndefined()
    seedBuiltins(env.ctx, rewardCapes())
    expect(grantPendingHalloweenRewards(env.ctx)).toBe(1)
    expect(grantPendingHalloweenRewards(env.ctx)).toBe(0)
    expect(one(env.ctx.db, "SELECT 1 AS x FROM user_capes WHERE uuid = ? AND cape_id = 'halloween_hunter_2026'", p.user.uuid)).toBeDefined()
  })
})

describe('trick or treat', () => {
  it('Berlin calendar day and next midnight (also across the clock change)', () => {
    expect(berlinDay(Date.UTC(2026, 9, 20, 21, 59, 0))).toBe('2026-10-20')
    expect(berlinDay(Date.UTC(2026, 9, 20, 22, 0, 30))).toBe('2026-10-21')
    expect(new Date(nextBerlinMidnight(Date.UTC(2026, 9, 20, 12, 0))).toISOString()).toBe('2026-10-20T22:00:00.000Z')
    // 25.10.: Winterzeit ab 03:00 CEST → Mitternacht danach ist 23:00 UTC.
    expect(new Date(nextBerlinMidnight(Date.UTC(2026, 9, 25, 12, 0))).toISOString()).toBe('2026-10-25T23:00:00.000Z')
    expect(new Date(nextBerlinMidnight(Date.UTC(2026, 9, 24, 23, 30))).toISOString()).toBe('2026-10-25T23:00:00.000Z')
    expect(new Date(nextBerlinMidnight(Date.UTC(2026, 10, 1, 23, 30))).toISOString()).toBe('2026-11-02T23:00:00.000Z')
  })

  it('allows one roll per Berlin day (409 trick_used + next), resets at Berlin midnight', async () => {
    const env = eventEnv()
    const p = await login(env, 'Knock')
    env.clock.t = Date.UTC(2026, 9, 20, 21, 59, 0)
    const r1 = trickOrTreat(env.ctx, p.user.uuid, () => 0)
    expect(r1).toEqual({ result: 'sour', next: '2026-10-20T22:00:00.000Z' })
    const again = await callRoute(env, trickRoute, { method: 'POST', url: '/v1/me/halloween/trick', headers: auth(p.token) })
    expect(again.status).toBe(409)
    expect(again.error!.code).toBe('trick_used')
    expect(again.error!.details).toEqual({ next: '2026-10-20T22:00:00.000Z' })
    expect(halloweenView(env.ctx, p.user.uuid).trick).toEqual({ usedToday: true, result: 'sour', next: '2026-10-20T22:00:00.000Z' })

    env.clock.t = Date.UTC(2026, 9, 20, 22, 0, 30)
    expect(halloweenView(env.ctx, p.user.uuid).trick.usedToday).toBe(false)
    // Würfe der Reihe nach: Saures? 1 (nein) → Topf-Teil 0 (Mondnacht-Umhang) → Farbe 1 (Mondsilber).
    const seq = [1, 0, 1]
    const r2 = trickOrTreat(env.ctx, p.user.uuid, () => seq.shift()!)
    expect(r2.result).toBe('sweet')
    if (r2.result !== 'sweet') throw new Error('expected sweet')
    expect(r2.reward).toEqual({ kind: 'cape', id: 'halloween_moon_silver', name: HALLOWEEN_CAPES[1].name })
    expect(one(env.ctx.db, "SELECT source FROM user_capes WHERE uuid = ? AND cape_id = 'halloween_moon_silver'", p.user.uuid)).toEqual({ source: 'reward' })
    expect(halloweenView(env.ctx, p.user.uuid).trick).toMatchObject({ usedToday: true, result: 'sweet', reward: { kind: 'cape', id: 'halloween_moon_silver' } })
    // Mit einer Farbe ist der Mondnacht-Umhang aus dem Topf.
    expect(unownedPool(env.ctx, p.user.uuid).map((x) => x.id)).not.toContain('mondnacht')
    expect(unownedPool(env.ctx, p.user.uuid)).toHaveLength(10)
  })

  it('is sour when all 16 items are owned, never gives an owned item, and needs the event', async () => {
    const env = eventEnv()
    const p = await login(env, 'Collector')
    const u = p.user.uuid
    expect(TRICK_POOL).toHaveLength(11)
    expect(unownedPool(env.ctx, u)).toHaveLength(11)
    grantCape(env.ctx, u, 'halloween_poison_green', 'reward')
    for (const e of ['count_bow', 'ghost_float', 'zombie_walk']) grantCosmetic(env.ctx, u, e, 'reward')
    for (const s of STICKERS) grantSticker(env.ctx, u, s.id, 'reward')
    expect(unownedPool(env.ctx, u)).toEqual([{ kind: 'emote', id: 'moon_howl', name: expect.anything() }])
    const r = trickOrTreat(env.ctx, u, (n) => n - 1)
    expect(r).toMatchObject({ result: 'sweet', reward: { kind: 'emote', id: 'moon_howl' } })
    expect(ownedEmotes(env.ctx, u)).toContain('moon_howl')

    env.clock.advance(24 * 3600_000)
    // Würfel sagt „Süßes“ – aber alles ist schon da → Saures.
    expect(trickOrTreat(env.ctx, u, () => 3).result).toBe('sour')

    setEventEnabled(env.ctx, ADMIN, 'halloween', false)
    env.clock.advance(24 * 3600_000)
    expect(code(() => trickOrTreat(env.ctx, u))).toBe('event_inactive')
  })

  it('crypto roll gives sour about one time in four', async () => {
    const env = eventEnv()
    let sour = 0
    const N = 200
    for (let i = 0; i < N; i++) {
      const p = await login(env, `R${i}`, `c${i.toString(16).padStart(31, '0')}`)
      if (trickOrTreat(env.ctx, p.user.uuid).result === 'sour') sour++
    }
    expect(sour).toBeGreaterThan(N * 0.12)
    expect(sour).toBeLessThan(N * 0.4)
  })
})

describe('Mondnacht: one colour per player', () => {
  it('refuses a second colour on every grant path, the same colour stays idempotent', async () => {
    const env = eventEnv()
    const p = await login(env, 'OneCape')
    expect(grantCape(env.ctx, p.user.uuid, 'halloween_blood_moon', 'reward')).toBe(true)
    expect(grantCape(env.ctx, p.user.uuid, 'halloween_blood_moon', 'admin')).toBe(false)
    for (const src of ['reward', 'admin', 'code'] as const) {
      expect(code(() => grantCape(env.ctx, p.user.uuid, 'halloween_ghost_white', src))).toBe('mondnacht_owned')
    }
    // Der Kürbisjäger-Umhang ist kein Mondnacht-Umhang.
    expect(grantCape(env.ctx, p.user.uuid, 'halloween_hunter_2026', 'reward')).toBe(true)
    // Würfeln gibt nie eine zweite Farbe: Topf-Teil 0 ist jetzt ein Emote.
    const seq = [1, 0, 0]
    const r = trickOrTreat(env.ctx, p.user.uuid, () => seq.shift() ?? 0)
    expect(r).toMatchObject({ result: 'sweet', reward: { kind: 'emote', id: 'count_bow' } })
    expect(all(env.ctx.db, "SELECT cape_id FROM user_capes WHERE uuid = ? AND cape_id LIKE 'halloween_%' AND cape_id <> 'halloween_hunter_2026'", p.user.uuid)).toHaveLength(1)
  })
})

describe('reward items', () => {
  it('are listed as reward while the event is on, not claimable, not code-redeemable', async () => {
    const env = eventEnv()
    const p = await login(env, 'Viewer')
    const cape = catalog(env.ctx, p.user.uuid).find((c) => c.id === 'halloween_blood_moon')!
    expect(cape).toMatchObject({ unlock: 'reward', rewardOf: 'halloween2026', owned: false })
    expect(code(() => claimCape(env.ctx, p.user.uuid, 'halloween_blood_moon'))).toBe('not_claimable')
    expect(code(() => createCodes(env.ctx, ADMIN, { capeId: 'halloween_blood_moon', maxUses: 1, count: 1 }))).toBe('not_code_redeemable')
    expect(code(() => createCodes(env.ctx, ADMIN, { cosmeticId: 'count_bow', maxUses: 1, count: 1 }))).toBe('not_code_redeemable')
    const emote = cosmeticCatalog(env.ctx, p.user.uuid).find((c) => c.id === 'count_bow')!
    expect(emote).toMatchObject({ unlock: 'reward', rewardOf: 'halloween2026', owned: false })
    expect(publicCapes(env.ctx).find((c) => c.id === 'halloween_ghost_white')).toMatchObject({ unlock: 'reward', rewardOf: 'halloween2026' })

    // Event aus: nur noch für Besitzer sichtbar.
    grantCape(env.ctx, p.user.uuid, 'halloween_blood_moon', 'reward')
    setEventEnabled(env.ctx, ADMIN, 'halloween', false)
    const ids = catalog(env.ctx, p.user.uuid).map((c) => c.id)
    expect(ids).toContain('halloween_blood_moon')
    expect(ids).not.toContain('halloween_ghost_white')
    expect(cosmeticCatalog(env.ctx, p.user.uuid).map((c) => c.id)).not.toContain('count_bow')
    expect(publicCapes(env.ctx).map((c) => c.id)).not.toContain('halloween_ghost_white')
  })
})

describe('stickers', () => {
  it('chat messages carry only owned stickers, alone, and are not editable', async () => {
    const env = eventEnv()
    const [a, b] = await players(env, 'Ann', 'Ben')
    befriend(env, a!, b!)
    const dm = openDm(env.ctx, a!.uuid, b!.uuid)
    expect(code(() => sendMessage(env.ctx, a!.uuid, dm.id, { sticker: 'ghost_boo' }))).toBe('sticker_locked')
    expect(code(() => sendMessage(env.ctx, a!.uuid, dm.id, { sticker: 'nope' }))).toBe('sticker_locked')
    grantSticker(env.ctx, a!.uuid, 'ghost_boo', 'reward')
    expect(ownedStickers(env.ctx, a!.uuid)).toEqual(['ghost_boo'])
    expect(code(() => sendMessage(env.ctx, a!.uuid, dm.id, { sticker: 'ghost_boo', text: 'hi' }))).toBe('sticker_conflict')
    const l = listen(env, b!.uuid)
    const { message } = sendMessage(env.ctx, a!.uuid, dm.id, { sticker: 'ghost_boo' })
    expect(message).toMatchObject({ sticker: 'ghost_boo', text: null, attachments: [] })
    expect(l.of('chat_message')[0]!.message.sticker).toBe('ghost_boo')
    expect(code(() => editMessage(env.ctx, a!.uuid, message.id, 'changed'))).toBe('sticker_not_editable')
    const reply = sendMessage(env.ctx, b!.uuid, dm.id, { text: 'lol', replyTo: message.id }).message
    expect(reply.replyTo).toMatchObject({ sticker: 'ghost_boo' })
    expect(reply.sticker).toBeNull()
  })

  it('GET /v1/me/cosmetics lists owned stickers', async () => {
    const env = eventEnv()
    const p = await login(env, 'Stick')
    grantSticker(env.ctx, p.user.uuid, 'grave_riplag', 'reward')
    grantSticker(env.ctx, p.user.uuid, 'pumpkin_hihi', 'reward')
    const r = await callRoute(env, cosmeticsRoute, { url: '/v1/me/cosmetics', headers: auth(p.token) })
    expect((r.body as { stickers: string[] }).stickers).toEqual(['pumpkin_hihi', 'grave_riplag'])
  })
})

describe('lookup: badges and fx', () => {
  it('shows profile badges (respecting showBadge) and fx only while the event runs', async () => {
    const env = eventEnv()
    const [viewer, subject] = await players(env, 'Viewer', 'Subject')
    ensureEventBadge(env.ctx, subject!.uuid)
    let e = lookupPlayers(env.ctx, viewer!.uuid, [subject!.uuid]).players[0]!
    expect(e).toMatchObject({ uuid: subject!.uuid, badges: ['halloween2026'], fx: ['hits', 'trail'] })

    setEffects(env.ctx, subject!.uuid, { hits: false, trail: true })
    e = lookupPlayers(env.ctx, viewer!.uuid, [subject!.uuid]).players[0]!
    expect(e.fx).toEqual(['trail'])

    updateSettings(env.ctx, subject!.uuid, { showBadge: false })
    e = lookupPlayers(env.ctx, viewer!.uuid, [subject!.uuid]).players[0]!
    expect(e.badges).toEqual([])
    expect(lookupPlayers(env.ctx, subject!.uuid, [subject!.uuid]).players[0]!.badges).toEqual(['halloween2026'])

    // Nach dem Ende: Abzeichen bleiben, Effekte weg.
    updateSettings(env.ctx, subject!.uuid, { showBadge: true })
    env.clock.t = Date.UTC(2026, 10, 3, 12, 0, 0)
    e = lookupPlayers(env.ctx, viewer!.uuid, [subject!.uuid]).players[0]!
    expect(e).toMatchObject({ badges: ['halloween2026'], fx: [] })
  })

  it('effects route validates and stores the switches', async () => {
    const env = eventEnv()
    const p = await login(env, 'Fx')
    const bad = await callRoute(env, effectsRoute, { method: 'PUT', url: '/v1/me/halloween/effects', headers: auth(p.token), body: JSON.stringify({ hits: 'yes' }) })
    expect(bad.status).toBe(400)
    const ok = await callRoute(env, effectsRoute, { method: 'PUT', url: '/v1/me/halloween/effects', headers: auth(p.token), body: JSON.stringify({ hits: false, trail: false }) })
    expect(ok.body).toEqual({ effects: { hits: false, trail: false } })
    const view = await callRoute(env, halloweenRoute, { url: '/v1/me/halloween', headers: auth(p.token) })
    expect(view.body).toMatchObject({ active: true, endsAt: '2026-11-02T23:00:00.000Z', effects: { hits: false, trail: false }, badges: ['halloween2026'] })
  })
})

describe('bundled halloween assets', () => {
  const ASSETS = join(__dirname, '..', 'assets')
  it('catalog has the 7 reward capes (HD, animated) and they seed', async () => {
    const list = await loadBuiltins(
      async () => JSON.parse(readFileSync(join(ASSETS, 'capes', 'catalog.json'), 'utf8')),
      async (n) => readFileSync(join(ASSETS, 'capes', n)),
    )
    const ours = list.filter((c) => c.reward === 'halloween2026')
    expect(ours.map((c) => c.id).sort()).toEqual([...HALLOWEEN_CAPES.map((c) => c.id), HUNTER_CAPE.id].sort())
    for (const c of ours) expect(c).toMatchObject({ unlock: 'admin', scale: 8, frames: 24, frameTimeMs: 250 })
    const env = makeEnv()
    seedBuiltins(env.ctx, list)
    expect(one(env.ctx.db, "SELECT reward, unlock FROM capes WHERE id = 'halloween_blood_moon'")).toEqual({ reward: 'halloween2026', unlock: 'admin' })
  })
  it('sticker and badge PNGs are the sprites ×8', () => {
    for (const s of STICKERS) {
      const p = PNG.sync.read(readFileSync(join(ASSETS, 'halloween', 'stickers', `${s.id}.png`)))
      expect([p.width, p.height]).toEqual([96, 96])
    }
    const mb = PNG.sync.read(readFileSync(join(ASSETS, 'halloween', 'badges', 'halloween2026.png')))
    expect([mb.width, mb.height]).toEqual([72, 64])
    const pk = PNG.sync.read(readFileSync(join(ASSETS, 'halloween', 'badges', 'pumpkinhunter2026.png')))
    expect([pk.width, pk.height]).toEqual([64, 64])
  })
})

/** Vier Test-Flügel (Modell = Hexenhut, nur Platz/Anhängepunkt/ID geändert). */
function seedWings(env: TestEnv, ids: readonly string[] = HALLOWEEN_WING_IDS) {
  const V2 = join(__dirname, '..', 'assets', 'cosmetics', 'v2')
  const base = JSON.parse(readFileSync(join(V2, 'witch_hat.json'), 'utf8'))
  seedBuiltinCosmetics(env.ctx, ids.map((id, i) => buildV2Cosmetic({
    id, name: id, unlock: 'admin', reward: 'pumpkinhunter2026', sort: 100 + i,
    frames: 1, glowFrames: base.glow ? (base.glow.frames ?? 1) : 0, glowFrameTimeMs: base.glow?.frameTimeMs ?? null,
    files: {
      modelJson: Buffer.from(JSON.stringify({ ...base, id, slot: 'wings', attach: 'body' })),
      texture: readFileSync(join(V2, 'witch_hat.png')),
      glow: base.glow ? readFileSync(join(V2, 'witch_hat-glow.png')) : null,
      card: readFileSync(join(V2, 'witch_hat-card.png')),
      cardNight: readFileSync(join(V2, 'witch_hat-card-night.png')),
    },
  })))
}

describe('pumpkin hunter wings', () => {
  it('v2 models: wings need attach body, head items attach head', () => {
    const V2 = join(__dirname, '..', 'assets', 'cosmetics', 'v2')
    const base = JSON.parse(readFileSync(join(V2, 'witch_hat.json'), 'utf8'))
    const files = (m: unknown) => ({
      modelJson: Buffer.from(JSON.stringify(m)), texture: readFileSync(join(V2, 'witch_hat.png')),
      glow: base.glow ? readFileSync(join(V2, 'witch_hat-glow.png')) : null,
      card: readFileSync(join(V2, 'witch_hat-card.png')), cardNight: readFileSync(join(V2, 'witch_hat-card-night.png')),
    })
    const entry = (m: Record<string, unknown>) => ({ id: 'w', name: 'w', unlock: 'free' as const, sort: 0, frames: 1, glowFrames: base.glow ? (base.glow.frames ?? 1) : 0, glowFrameTimeMs: base.glow?.frameTimeMs ?? null, files: files({ ...base, id: 'w', ...m }) })
    expect(() => buildV2Cosmetic(entry({ slot: 'wings', attach: 'body' }))).not.toThrow()
    expect(() => buildV2Cosmetic(entry({ slot: 'wings', attach: 'head' }))).toThrow(/attach body/)
    expect(() => buildV2Cosmetic(entry({ slot: 'hat', attach: 'body' }))).toThrow(/attach head/)
  })

  it('after 13 pumpkins exactly one wing can be chosen, equipped and seen in the lookup', async () => {
    const env = eventEnv()
    seedWings(env)
    const [p, viewer] = await players(env, 'Winged', 'Looker')
    const u = p!.uuid
    expect(halloweenView(env.ctx, u).wings).toEqual({ choice: null, canChoose: false, options: [...HALLOWEEN_WING_IDS] })
    const token = (await login(env, 'Winged', u)).token
    const early = await callRoute(env, wingsRoute, { method: 'POST', url: '/v1/me/halloween/wings', headers: auth(token), body: JSON.stringify({ id: 'raven_wings' }) })
    expect(early.error!.code).toBe('hunt_incomplete')
    for (const id of PUMPKINS) findPumpkin(env.ctx, u, id)
    expect(halloweenView(env.ctx, u).wings.canChoose).toBe(true)
    const unknown = await callRoute(env, wingsRoute, { method: 'POST', url: '/v1/me/halloween/wings', headers: auth(token), body: JSON.stringify({ id: 'redstone_wings' }) })
    expect(unknown.error!.code).toBe('wings_unknown')
    const ok = await callRoute(env, wingsRoute, { method: 'POST', url: '/v1/me/halloween/wings', headers: auth(token), body: JSON.stringify({ id: 'raven_wings' }) })
    expect(ok.body).toEqual({ wings: { choice: 'raven_wings', canChoose: false, options: [...HALLOWEEN_WING_IDS] } })
    const again = await callRoute(env, wingsRoute, { method: 'POST', url: '/v1/me/halloween/wings', headers: auth(token), body: JSON.stringify({ id: 'bat_wings' }) })
    expect(again.status).toBe(409)
    expect(again.error!.code).toBe('wings_chosen')
    // Kein zweiter Flügel über andere Wege.
    expect(code(() => grantCosmetic(env.ctx, u, 'bat_wings', 'admin'))).toBe('wings_chosen')
    expect(code(() => grantCosmetic(env.ctx, u, 'ghost_wings', 'code'))).toBe('wings_chosen')
    expect(code(() => equipCosmetics(env.ctx, u, { wings: 'bat_wings' }))).toBe('cosmetic_locked')
    // Anlegen im Platz wings, im Lookup als v2-Modell.
    expect(code(() => equipCosmetics(env.ctx, u, { hat: 'raven_wings' }))).toBe('wrong_slot')
    equipCosmetics(env.ctx, u, { wings: 'raven_wings' })
    const e = lookupPlayers(env.ctx, viewer!.uuid, [u]).players[0]!
    expect(e.cosmetics.wings).toMatchObject({ id: 'raven_wings', format: 2 })
    expect(e.cosmetics.wings!.model).toContain('/v1/cosmetics/raven_wings/model.json')
    updateSettings(env.ctx, u, { showCosmeticsToOthers: false })
    expect(lookupPlayers(env.ctx, viewer!.uuid, [u]).players[0]?.cosmetics.wings ?? null).toBeNull()
    expect(cosmeticCatalog(env.ctx, u).find((c) => c.id === 'raven_wings')).toMatchObject({ slot: 'wings', unlock: 'reward', rewardOf: 'pumpkinhunter2026', owned: true })
  })

  it('options only list imported wings; without models nothing can be chosen', async () => {
    const env = eventEnv()
    const p = await login(env, 'NoModels')
    for (const id of PUMPKINS) findPumpkin(env.ctx, p.user.uuid, id)
    expect(halloweenView(env.ctx, p.user.uuid).wings).toEqual({ choice: null, canChoose: false, options: [] })
    seedWings(env, ['bat_wings'])
    expect(halloweenView(env.ctx, p.user.uuid).wings).toEqual({ choice: null, canChoose: true, options: ['bat_wings'] })
  })
})

void OWNER
