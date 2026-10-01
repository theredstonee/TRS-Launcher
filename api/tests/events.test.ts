import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { DatabaseSync } from 'node:sqlite'
import { describe, expect, it } from 'vitest'
import { loadBuiltinCosmetics, loadBuiltins } from '../server/lib/builtin'
import { catalog, seedBuiltins } from '../server/lib/capes'
import { cosmeticCatalog, equipCosmetics, seedBuiltinCosmetics } from '../server/lib/cosmetics'
import { all, migrate, one, run } from '../server/lib/db'
import { activeEventsFor, adminEvents, setEventEnabled } from '../server/lib/liveevents'
import { lookupPlayers } from '../server/lib/lookup'
import { MIGRATIONS, migrateEvents } from '../server/lib/migrations'
import { publicCapes, publicCompanions, publicHats } from '../server/lib/site'
import { ownerStaff, setMemberRoles } from '../server/lib/team'
import adminDelete from '../server/routes/v1/admin/events/[id]/players/[uuid].delete'
import adminAdd from '../server/routes/v1/admin/events/[id]/players/index.post'
import adminPut from '../server/routes/v1/admin/events/[id].put'
import adminList from '../server/routes/v1/admin/events/index.get'
import publicRoute from '../server/routes/v1/events/index.get'
import claimCapeRoute from '../server/routes/v1/me/capes/[id]/claim.post'
import claimRoute from '../server/routes/v1/me/cosmetics/[id]/claim.post'
import putCosmetics from '../server/routes/v1/me/cosmetics.put'
import meRoute from '../server/routes/v1/me/index.get'
import { callRoute } from './circuithelpers'
import { listen, players } from './chathelpers'
import { ADMIN, login, makeEnv, type TestEnv } from './helpers'

const ASSETS = join(__dirname, '..', 'assets')
const OWNER = ownerStaff(ADMIN)

async function seedBundled(env: TestEnv) {
  const readFile = (dir: string) => async (name: string) => {
    try {
      return readFileSync(join(ASSETS, dir, name))
    } catch {
      return null
    }
  }
  const json = (dir: string) => async () => JSON.parse(readFileSync(join(ASSETS, dir, 'catalog.json'), 'utf8'))
  seedBuiltinCosmetics(env.ctx, await loadBuiltinCosmetics(json('cosmetics'), readFile('cosmetics')))
  seedBuiltins(env.ctx, await loadBuiltins(json('capes'), readFile('capes')))
}

const auth = (token: string) => ({ authorization: `Bearer ${token}`, 'content-type': 'application/json' })

/** Admin (Team-Rolle admin), Moderator und zwei Spieler mit Token. */
async function crew(env: TestEnv) {
  await login(env, 'Owner', ADMIN)
  const a = await login(env, 'Alice')
  const m = await login(env, 'Mod')
  const p = await login(env, 'Player')
  const q = await login(env, 'Quinn')
  setMemberRoles(env.ctx, OWNER, a.user.uuid, ['admin'])
  setMemberRoles(env.ctx, OWNER, m.user.uuid, ['moderator'])
  return { a, m, p, q }
}

describe('migration 21: events', () => {
  it('creates the tables, seeds halloween (off), grants events.manage to owner/admin only, is idempotent', () => {
    const env = makeEnv()
    const db = env.ctx.db
    expect(one<{ v: number }>(db, 'SELECT MAX(version) AS v FROM schema_migrations')!.v).toBe(MIGRATIONS.at(-1)!.version)
    expect(MIGRATIONS.some((m) => m.version === 21)).toBe(true)
    expect(all(db, 'SELECT id, enabled FROM events')).toEqual([{ id: 'halloween', enabled: 0 }])
    const perms = (id: string) => JSON.parse(one<{ permissions: string }>(db, 'SELECT permissions FROM team_roles WHERE id = ?', id)!.permissions) as string[]
    expect(perms('owner')).toContain('events.manage')
    expect(perms('admin')).toContain('events.manage')
    expect(perms('moderator')).not.toContain('events.manage')
    expect(perms('content')).not.toContain('events.manage')
    migrateEvents(db)
    migrateEvents(db)
    expect(all(db, 'SELECT id FROM events')).toHaveLength(1)
    expect(perms('admin').filter((p) => p === 'events.manage')).toHaveLength(1)
  })

  it('keeps existing grants and equipment when the leaf tables are rebuilt, and allows event/companion', async () => {
    const env = makeEnv()
    await seedBundled(env)
    const u = (await login(env, 'Steve')).user.uuid
    const db = env.ctx.db
    // Auf den alten Stand zurückbauen (strenge CHECKs wie vor Migration 21), dann erneut migrieren.
    db.exec(`
DROP TABLE equipped_cosmetics; DROP TABLE user_cosmetics; DROP TABLE user_capes;
CREATE TABLE user_cosmetics (uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE, cosmetic_id TEXT NOT NULL REFERENCES cosmetics(id) ON DELETE CASCADE, source TEXT NOT NULL CHECK (source IN ('code', 'admin')), granted_at INTEGER NOT NULL, PRIMARY KEY (uuid, cosmetic_id));
CREATE TABLE user_capes (uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE, cape_id TEXT NOT NULL REFERENCES capes(id) ON DELETE CASCADE, source TEXT NOT NULL CHECK (source IN ('code', 'admin')), granted_at INTEGER NOT NULL, PRIMARY KEY (uuid, cape_id));
CREATE TABLE equipped_cosmetics (uuid TEXT NOT NULL REFERENCES users(uuid) ON DELETE CASCADE, slot TEXT NOT NULL CHECK (slot IN ('hat', 'wings', 'back', 'aura')), cosmetic_id TEXT NOT NULL REFERENCES cosmetics(id) ON DELETE CASCADE, PRIMARY KEY (uuid, slot));
`)
    run(db, "INSERT INTO user_cosmetics VALUES (?, 'trs_cap', 'code', 5)", u)
    run(db, "INSERT INTO user_capes VALUES (?, 'veteran', 'admin', 6)", u)
    run(db, "INSERT INTO equipped_cosmetics VALUES (?, 'hat', 'trs_cap')", u)
    migrateEvents(db)
    expect(all(db, 'SELECT * FROM user_cosmetics')).toEqual([{ uuid: u, cosmetic_id: 'trs_cap', source: 'code', granted_at: 5 }])
    expect(all(db, 'SELECT * FROM user_capes')).toEqual([{ uuid: u, cape_id: 'veteran', source: 'admin', granted_at: 6 }])
    expect(all(db, 'SELECT * FROM equipped_cosmetics')).toEqual([{ uuid: u, slot: 'hat', cosmetic_id: 'trs_cap' }])
    run(db, "INSERT INTO user_cosmetics VALUES (?, 'witch_hat', 'event', 7)", u)
    run(db, "INSERT INTO user_capes VALUES (?, 'halloween', 'event', 7)", u)
    run(db, "INSERT INTO equipped_cosmetics VALUES (?, 'companion', 'bat_buddy')", u)
    expect(db.prepare('PRAGMA foreign_key_check').all()).toEqual([])
  })

  it('a fresh database reaches version 21', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('PRAGMA foreign_keys = ON')
    migrate(db)
    expect(one<{ v: number }>(db, 'SELECT MAX(version) AS v FROM schema_migrations')!.v).toBe(21)
  })
})

describe('GET /v1/events (public)', () => {
  it('lists only the global state, cacheable for 60 s, no login needed', async () => {
    const env = makeEnv()
    const off = await callRoute(env, publicRoute, { url: '/v1/events' })
    expect(off.body).toEqual({ events: [{ id: 'halloween', active: false }] })
    expect(String(off.headers['cache-control'])).toContain('max-age=60')
    setEventEnabled(env.ctx, ADMIN, 'halloween', true)
    expect((await callRoute(env, publicRoute, { url: '/v1/events' })).body).toEqual({ events: [{ id: 'halloween', active: true }] })
  })

  it('a single player allowlist does not show up in the public list', async () => {
    const env = makeEnv()
    const u = (await login(env, 'Steve')).user.uuid
    run(env.ctx.db, "INSERT INTO event_players (event_id, player_uuid, player_name, added_at) VALUES ('halloween', ?, 'Steve', 1)", u)
    expect((await callRoute(env, publicRoute, { url: '/v1/events' })).body).toEqual({ events: [{ id: 'halloween', active: false }] })
  })
})

describe('/v1/me events', () => {
  it('is global OR in the allowlist', async () => {
    const env = makeEnv()
    const a = await login(env, 'Steve')
    const b = await login(env, 'Alex')
    const me = async (t: string) => (await callRoute(env, meRoute, { url: '/v1/me', headers: auth(t) })).body as { events: string[] }
    expect((await me(a.token)).events).toEqual([])
    run(env.ctx.db, "INSERT INTO event_players (event_id, player_uuid, player_name, added_at) VALUES ('halloween', ?, 'Steve', 1)", a.user.uuid)
    expect((await me(a.token)).events).toEqual(['halloween'])
    expect((await me(b.token)).events).toEqual([])
    setEventEnabled(env.ctx, ADMIN, 'halloween', true)
    expect((await me(b.token)).events).toEqual(['halloween'])
  })
})

describe('admin routes', () => {
  it('need events.manage', async () => {
    const env = makeEnv()
    const { a, m, p } = await crew(env)
    expect((await callRoute(env, adminList, { url: '/v1/admin/events', headers: auth(p.token) })).error?.status).toBe(403)
    expect((await callRoute(env, adminList, { url: '/v1/admin/events', headers: auth(m.token) })).error?.code).toBe('missing_permission')
    const put = (tok: string) => callRoute(env, adminPut, { method: 'PUT', url: '/v1/admin/events/halloween', params: { id: 'halloween' }, body: '{"enabled":true}', headers: auth(tok) })
    expect((await put(m.token)).error?.code).toBe('missing_permission')
    expect(adminEvents(env.ctx)[0]!.enabled).toBe(false)
    const ok = await put(a.token)
    expect(ok.body).toMatchObject({ id: 'halloween', enabled: true })
    expect((await callRoute(env, adminList, { url: '/v1/admin/events', headers: auth(a.token) })).body).toMatchObject([{ id: 'halloween', enabled: true, players: [] }])
  })

  it('validates the body and the event id', async () => {
    const env = makeEnv()
    const { a } = await crew(env)
    const put = (id: string, body: string) => callRoute(env, adminPut, { method: 'PUT', url: `/v1/admin/events/${id}`, params: { id }, body, headers: auth(a.token) })
    expect((await put('halloween', '{"enabled":"yes"}')).error?.status).toBe(400)
    expect((await put('halloween', '{"enabled":true,"x":1}')).error?.status).toBe(400)
    expect((await put('nope', '{"enabled":true}')).error?.code).toBe('event_not_found')
    expect((await put('Bad Id!', '{"enabled":true}')).error?.status).toBe(404)
  })

  it('adds players by name (TRS account or Mojang) and by uuid, rejects duplicates, removes them, writes the audit log', async () => {
    const env = makeEnv()
    const { a, p } = await crew(env)
    env.mojang.accounts.set('ghost', { uuid: 'c'.repeat(32), name: 'Ghost', model: 'classic', skinUrl: null, capeUrl: null })
    const add = (body: unknown) => callRoute(env, adminAdd, { method: 'POST', url: '/v1/admin/events/halloween/players', params: { id: 'halloween' }, body: JSON.stringify(body), headers: auth(a.token) })
    const r1 = await add({ name: 'player' })
    expect(r1.status).toBe(201)
    expect((r1.body as { players: { uuid: string, name: string }[] }).players).toMatchObject([{ uuid: p.user.uuid, name: 'Player' }])
    expect((await add({ name: 'Player' })).error?.code).toBe('already_added')
    const r2 = await add({ name: 'Ghost' })
    expect((r2.body as { players: { name: string }[] }).players.map((x) => x.name)).toEqual(['Player', 'Ghost'])
    expect((await add({ name: 'Nobody' })).error?.code).toBe('player_not_found')
    env.mojang.fail = true
    expect((await add({ name: 'Zed' })).error?.status).toBe(502)
    env.mojang.fail = false
    const dashed = ['d'.repeat(8), 'd'.repeat(4), 'd'.repeat(4), 'd'.repeat(4), 'd'.repeat(12)].join('-')
    expect((await add({ uuid: dashed })).status).toBe(201)
    expect((await add({ uuid: 'z'.repeat(32) })).error?.status).toBe(404)
    expect((await add({ name: 'a b' })).error?.status).toBe(400)
    expect(activeEventsFor(env.ctx, p.user.uuid)).toEqual(['halloween'])
    const del = (uuid: string) => callRoute(env, adminDelete, { method: 'DELETE', url: `/v1/admin/events/halloween/players/${uuid}`, params: { id: 'halloween', uuid }, headers: auth(a.token) })
    const d = await del(p.user.uuid)
    expect((d.body as { players: { uuid: string }[] }).players.some((x) => x.uuid === p.user.uuid)).toBe(false)
    expect((await del(p.user.uuid)).error?.code).toBe('player_not_found')
    expect(activeEventsFor(env.ctx, p.user.uuid)).toEqual([])
    const log = all<{ action: string }>(env.ctx.db, "SELECT action FROM admin_log WHERE action LIKE 'event.%' ORDER BY id").map((x) => x.action)
    expect(log).toEqual(['event.player_add', 'event.player_add', 'event.player_add', 'event.player_remove'])
  })

  it('pushes events_changed with what is active for each recipient', async () => {
    const env = makeEnv()
    const { a, p, q } = await crew(env)
    const lp = listen(env, p.user.uuid)
    const lq = listen(env, q.user.uuid)
    await callRoute(env, adminAdd, { method: 'POST', url: '/v1/admin/events/halloween/players', params: { id: 'halloween' }, body: JSON.stringify({ name: 'Player' }), headers: auth(a.token) })
    expect(lp.of('events_changed')).toEqual([{ type: 'events_changed', events: ['halloween'] }])
    expect(lq.of('events_changed')).toEqual([])
    lp.clear()
    const put = (enabled: boolean) => callRoute(env, adminPut, { method: 'PUT', url: '/v1/admin/events/halloween', params: { id: 'halloween' }, body: JSON.stringify({ enabled }), headers: auth(a.token) })
    await put(true)
    expect(lp.of('events_changed')).toEqual([{ type: 'events_changed', events: ['halloween'] }])
    expect(lq.of('events_changed')).toEqual([{ type: 'events_changed', events: ['halloween'] }])
    lp.clear()
    lq.clear()
    // Global aus: nur wer auf der Liste steht, behält es.
    await put(false)
    expect(lp.of('events_changed')).toEqual([{ type: 'events_changed', events: ['halloween'] }])
    expect(lq.of('events_changed')).toEqual([{ type: 'events_changed', events: [] }])
    // Gleicher Zustand noch einmal: nichts Neues.
    lq.clear()
    await put(false)
    expect(lq.of('events_changed')).toEqual([])
  })
})

describe('event cosmetics: catalog, claim, companion', () => {
  const claim = (env: TestEnv, tok: string, id: string) => callRoute(env, claimRoute, { method: 'POST', url: `/v1/me/cosmetics/${id}/claim`, params: { id }, headers: auth(tok) })
  const claimCape = (env: TestEnv, tok: string, id: string) => callRoute(env, claimCapeRoute, { method: 'POST', url: `/v1/me/capes/${id}/claim`, params: { id }, headers: auth(tok) })

  it('bundled event items load as format 2 / animated cape with the event field', async () => {
    const env = makeEnv()
    await seedBundled(env)
    const row = (id: string) => one<{ event: string | null, unlock: string }>(env.ctx.db, 'SELECT event, unlock FROM cosmetics WHERE id = ?', id)!
    for (const id of ['witch_hat', 'pumpkin_head', 'bat_buddy']) expect(row(id)).toEqual({ event: 'halloween', unlock: 'admin' })
    expect(one(env.ctx.db, "SELECT event, frames, frame_time_ms, width, height FROM capes WHERE id = 'halloween'"))
      .toEqual({ event: 'halloween', frames: 4, frame_time_ms: 220, width: 512, height: 256 })
  })

  it('catalog shows event items only while active (or when owned) and as unlock "event"', async () => {
    const env = makeEnv()
    await seedBundled(env)
    const u = (await login(env, 'Steve')).user.uuid
    expect(cosmeticCatalog(env.ctx, u).map((c) => c.id)).not.toContain('witch_hat')
    expect(catalog(env.ctx, u).map((c) => c.id)).not.toContain('halloween')
    setEventEnabled(env.ctx, ADMIN, 'halloween', true)
    const bat = cosmeticCatalog(env.ctx, u).find((c) => c.id === 'bat_buddy')!
    expect(bat).toMatchObject({ slot: 'companion', unlock: 'event', event: 'halloween', owned: false, format: 2 })
    expect(cosmeticCatalog(env.ctx, u).find((c) => c.id === 'witch_hat')).toMatchObject({ slot: 'hat', unlock: 'event', owned: false })
    expect(catalog(env.ctx, u).find((c) => c.id === 'halloween')).toMatchObject({ unlock: 'event', event: 'halloween', owned: false, animated: true, frames: 4, scale: 8 })
  })

  it('claim: 403 event_inactive, then 200 owned (idempotent), kept after the event ends', async () => {
    const env = makeEnv()
    await seedBundled(env)
    const s = await login(env, 'Steve')
    const u = s.user.uuid
    expect((await claim(env, s.token, 'witch_hat')).error).toMatchObject({ status: 403, code: 'event_inactive' })
    expect((await claimCape(env, s.token, 'halloween')).error?.code).toBe('event_inactive')
    // Freigabe nur für diesen Spieler reicht.
    run(env.ctx.db, "INSERT INTO event_players (event_id, player_uuid, player_name, added_at) VALUES ('halloween', ?, 'Steve', 1)", u)
    expect((await claim(env, s.token, 'witch_hat')).body).toEqual({ owned: true })
    expect((await claim(env, s.token, 'witch_hat')).body).toEqual({ owned: true })
    expect((await claimCape(env, s.token, 'halloween')).body).toEqual({ owned: true })
    expect(all(env.ctx.db, 'SELECT cosmetic_id, source FROM user_cosmetics WHERE uuid = ?', u)).toEqual([{ cosmetic_id: 'witch_hat', source: 'event' }])
    // Event vorbei: behalten, aber nichts Neues.
    run(env.ctx.db, 'DELETE FROM event_players')
    expect((await claim(env, s.token, 'witch_hat')).body).toEqual({ owned: true })
    expect((await claim(env, s.token, 'pumpkin_head')).error?.code).toBe('event_inactive')
    expect(cosmeticCatalog(env.ctx, u).find((c) => c.id === 'witch_hat')).toMatchObject({ owned: true })
    expect(cosmeticCatalog(env.ctx, u).some((c) => c.id === 'pumpkin_head')).toBe(false)
    expect(catalog(env.ctx, u).find((c) => c.id === 'halloween')).toMatchObject({ owned: true })
  })

  it('claim rejects unknown and non-event items and anonymous calls', async () => {
    const env = makeEnv()
    await seedBundled(env)
    const s = await login(env, 'Steve')
    expect((await claim(env, s.token, 'nope_item')).error?.code).toBe('cosmetic_not_found')
    expect((await claim(env, s.token, 'trs_cap')).error?.code).toBe('not_claimable')
    expect((await claimCape(env, s.token, 'redstone')).error?.code).toBe('not_claimable')
    expect((await claimCape(env, s.token, 'nope')).error?.code).toBe('cape_not_found')
    const anon = await callRoute(env, claimRoute, { method: 'POST', url: '/v1/me/cosmetics/witch_hat/claim', params: { id: 'witch_hat' } })
    expect(anon.error?.status).toBe(401)
  })

  it('PUT /v1/me/cosmetics takes companion (only owned, only the companion slot), together with a hat', async () => {
    const env = makeEnv()
    await seedBundled(env)
    setEventEnabled(env.ctx, ADMIN, 'halloween', true)
    const s = await login(env, 'Steve')
    const put = (body: unknown) => callRoute(env, putCosmetics, { method: 'PUT', url: '/v1/me/cosmetics', body: JSON.stringify(body), headers: auth(s.token) })
    expect((await put({ companion: 'bat_buddy' })).error?.code).toBe('cosmetic_locked')
    await claim(env, s.token, 'bat_buddy')
    await claim(env, s.token, 'witch_hat')
    expect((await put({ hat: 'bat_buddy' })).error?.code).toBe('wrong_slot')
    expect((await put({ companion: 'witch_hat' })).error?.code).toBe('wrong_slot')
    const ok = await put({ hat: 'witch_hat', companion: 'bat_buddy' })
    expect(ok.body).toMatchObject({ equipped: { hat: { id: 'witch_hat', slot: 'hat' }, companion: { id: 'bat_buddy', slot: 'companion', format: 2 }, wings: null } })
    // Lookup: companion in derselben Form wie hat bei v2.
    const l = lookupPlayers(env.ctx, s.user.uuid, [s.user.uuid]).players[0]!
    expect(l.cosmetics.hat).toMatchObject({ id: 'witch_hat', format: 2 })
    expect(l.cosmetics.companion).toMatchObject({ id: 'bat_buddy', format: 2, glowFrames: 12, glowFrameTimeMs: 150, scale: 8 })
    expect(Object.keys(l.cosmetics.companion!).sort()).toEqual(Object.keys(l.cosmetics.hat!).sort())
    expect(l.cosmetics.companion!.model).toMatch(/\/v1\/cosmetics\/bat_buddy\/model\.json\?v=/)
    // Ablegen nur des Begleiters.
    const off = await put({ companion: null })
    expect(off.body).toMatchObject({ equipped: { hat: { id: 'witch_hat' }, companion: null } })
    expect((await put({})).error?.status).toBe(400)
  })

  it('other players see the companion of someone else (lookup)', async () => {
    const env = makeEnv()
    await seedBundled(env)
    const [a, b] = await players(env, 'Alice', 'Bob')
    run(env.ctx.db, "INSERT INTO user_cosmetics VALUES (?, 'bat_buddy', 'event', 1)", a!.uuid)
    equipCosmetics(env.ctx, a!.uuid, { companion: 'bat_buddy' })
    expect(lookupPlayers(env.ctx, b!.uuid, [a!.uuid]).players[0]!.cosmetics.companion).toMatchObject({ id: 'bat_buddy' })
  })

  it('website lists show event items only while globally active, companions separately', async () => {
    const env = makeEnv()
    await seedBundled(env)
    expect(publicHats(env.ctx).map((h) => h.id)).not.toContain('witch_hat')
    expect(publicCompanions(env.ctx)).toEqual([])
    expect(publicCapes(env.ctx).map((c) => c.id)).not.toContain('halloween')
    // Nur Einzelfreigabe → die anonyme Website bleibt neutral.
    const u = (await login(env, 'Steve')).user.uuid
    run(env.ctx.db, "INSERT INTO event_players (event_id, player_uuid, player_name, added_at) VALUES ('halloween', ?, 'S', 1)", u)
    expect(publicCompanions(env.ctx)).toEqual([])
    setEventEnabled(env.ctx, ADMIN, 'halloween', true)
    expect(publicHats(env.ctx).filter((h) => h.event).map((h) => [h.id, h.slot, h.unlock])).toEqual([['witch_hat', 'hat', 'event'], ['pumpkin_head', 'hat', 'event']])
    expect(publicCompanions(env.ctx).map((h) => [h.id, h.slot, h.unlock, h.event])).toEqual([['bat_buddy', 'companion', 'event', 'halloween']])
    expect(publicCapes(env.ctx).find((c) => c.id === 'halloween')).toMatchObject({ unlock: 'event', event: 'halloween', frames: 4, scale: 8 })
  })
})
