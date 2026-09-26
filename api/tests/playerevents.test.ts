import { DatabaseSync } from 'node:sqlite'
import { describe, expect, it } from 'vitest'
import { approveCosmetic, banUser, rejectCosmetic } from '../server/lib/admin'
import { setActiveCape } from '../server/lib/capes'
import { equipCosmetics, grantCosmetic, uploadCosmetic } from '../server/lib/cosmetics'
import { all, migrate, one, run } from '../server/lib/db'
import { playEmote } from '../server/lib/emote-play'
import { block } from '../server/lib/friends'
import { createMojangClient } from '../server/lib/mojang'
import { MIGRATIONS } from '../server/lib/migrations'
import { emitSkin } from '../server/lib/playerevents'
import { parseWith } from '../server/lib/http'
import { createCodesBody, equipBody, playerStreamQuery } from '../server/lib/schemas'
import type { PlayerEvent } from '../server/lib/watch'
import { ADMIN, login, makeEnv, seedCosmeticFixtures, seedFixtures, templatePng, type TestEnv } from './helpers'

function code(fn: () => unknown): string {
  try {
    fn()
  } catch (e) {
    return (e as { code?: string }).code ?? (e as Error).message
  }
  return 'ok'
}

/** Öffnet einen Spieler-Stream wie `GET /v1/events/players` und sammelt die Ereignisse. */
function watch(env: TestEnv, viewer: string, uuids: string[]) {
  const got: PlayerEvent[] = []
  const state = { kicked: false }
  const sub = env.ctx.watch.subscribe(viewer, uuids, (e) => got.push(e), () => {
    state.kicked = true
  })
  if (!sub) throw new Error('subscribe refused')
  return { got, sub, state }
}

async function players(env: TestEnv, ...names: string[]) {
  const out: string[] = []
  for (const n of names) out.push((await login(env, n)).user.uuid)
  return out
}

describe('emotes', () => {
  it('free emotes play, locked and unknown ones fail, at most one per 2 s', async () => {
    const env = makeEnv()
    const [u] = await players(env, 'Steve')
    expect(playEmote(env.ctx, u!, 'winken')).toEqual({ emote: 'winken', durationMs: 2000, at: new Date(env.clock.t).toISOString() })
    expect(code(() => playEmote(env.ctx, u!, 'klatschen'))).toBe('rate_limited')
    env.clock.advance(1000)
    expect(code(() => playEmote(env.ctx, u!, 'klatschen'))).toBe('rate_limited')
    env.clock.advance(1000)
    expect(playEmote(env.ctx, u!, 'klatschen').emote).toBe('klatschen')
    env.clock.advance(2000)
    expect(code(() => playEmote(env.ctx, u!, 'tanzen'))).toBe('emote_locked')
    expect(code(() => playEmote(env.ctx, u!, 'redstone_tanz'))).toBe('emote_locked')
    expect(code(() => playEmote(env.ctx, u!, 'nope'))).toBe('emote_not_found')
    expect(code(() => playEmote(env.ctx, u!, 'free_crown'))).toBe('emote_not_found')
    // Fehlversuche verbrauchen das Limit nicht
    expect(playEmote(env.ctx, u!, 'jubeln').emote).toBe('jubeln')
    env.clock.advance(2000)
    grantCosmetic(env.ctx, u!, 'tanzen', 'admin')
    expect(playEmote(env.ctx, u!, 'tanzen').durationMs).toBe(6000)
    env.clock.advance(2000)
    expect(playEmote(env.ctx, ADMIN, 'redstone_tanz').emote).toBe('redstone_tanz')
  })
})

describe('player event fan-out', () => {
  it('delivers only events of watched players', async () => {
    const env = makeEnv()
    const [a, b, c, viewer] = await players(env, 'Alpha', 'Beta', 'Gamma', 'Viewer')
    const w = watch(env, viewer!, [a!, b!])
    const other = watch(env, c!, [c!])
    playEmote(env.ctx, a!, 'winken')
    playEmote(env.ctx, c!, 'winken')
    emitSkin(env.ctx, b!)
    expect(w.got.map((e) => [e.type, e.uuid])).toEqual([['emote', a], ['skin', b]])
    expect(w.got[0]).toMatchObject({ type: 'emote', emote: 'winken', durationMs: 2000 })
    expect(other.got.map((e) => [e.type, e.uuid])).toEqual([['emote', c]])
    w.sub.close()
    env.clock.advance(3000)
    playEmote(env.ctx, a!, 'winken')
    expect(w.got).toHaveLength(2)
    expect(env.ctx.watch.size).toBe(1)
  })

  it('blocked watchers get nothing; banned players emit nothing', async () => {
    const env = makeEnv()
    const [a, v1, v2] = await players(env, 'Alpha', 'Watcher', 'Blocked')
    const w1 = watch(env, v1!, [a!])
    const w2 = watch(env, v2!, [a!])
    block(env.ctx, a!, { uuid: v2! })
    playEmote(env.ctx, a!, 'winken')
    expect(w1.got).toHaveLength(1)
    expect(w2.got).toHaveLength(0)
    banUser(env.ctx, 'api-key', a!, undefined)
    // Sperre: Beobachter vergessen Abzeichen, Umhang und Kosmetik sofort (§22.3), Blockierte bekommen nichts.
    expect(w1.got.slice(1).map((e) => e.type)).toEqual(['badge', 'cape', 'cosmetics'])
    expect(w1.got[1]).toMatchObject({ badge: false })
    expect(w2.got).toHaveLength(0)
    emitSkin(env.ctx, a!)
    expect(w1.got).toHaveLength(4)
  })

  it('cosmetics/cape events carry the view the watcher may see (pending only for self)', async () => {
    const env = makeEnv()
    seedFixtures(env)
    seedCosmeticFixtures(env)
    const [a, viewer] = await players(env, 'Alpha', 'Viewer')
    const self = watch(env, a!, [a!])
    const w = watch(env, viewer!, [a!])
    const up = uploadCosmetic(env.ctx, a!, templatePng(env, 'crown'), { template: 'crown' })
    equipCosmetics(env.ctx, a!, { hat: up.id, aura: 'free_aura' })
    expect(self.got.at(-1)).toMatchObject({ type: 'cosmetics', uuid: a, cosmetics: { hat: { id: up.id }, aura: { id: 'free_aura' } } })
    expect(w.got.at(-1)).toMatchObject({ type: 'cosmetics', uuid: a, cosmetics: { hat: null, aura: { id: 'free_aura', template: 'orbit' } } })
    // Freigabe → andere sehen den Hut jetzt
    approveCosmetic(env.ctx, ADMIN, up.id)
    expect(w.got.at(-1)).toMatchObject({ type: 'cosmetics', cosmetics: { hat: { id: up.id } } })
    // Ablehnung → abgelegt
    rejectCosmetic(env.ctx, ADMIN, up.id, undefined)
    expect(w.got.at(-1)).toMatchObject({ type: 'cosmetics', cosmetics: { hat: null } })
    // Gleiches Teil erneut anlegen → kein Ereignis
    const n = w.got.length
    equipCosmetics(env.ctx, a!, { aura: 'free_aura' })
    expect(w.got).toHaveLength(n)
    // Umhang
    setActiveCape(env.ctx, a!, 'redstone')
    expect(w.got.at(-1)).toMatchObject({ type: 'cape', uuid: a, cape: { id: 'redstone', scale: 2 } })
    setActiveCape(env.ctx, a!, null)
    expect(w.got.at(-1)).toEqual({ type: 'cape', uuid: a, cape: null })
    expect(w.got).toHaveLength(n + 2)
  })

  it('limits streams per account and kicks them on logout-all/ban', async () => {
    const env = makeEnv()
    const [a, b] = await players(env, 'Alpha', 'Beta')
    const subs = [1, 2, 3].map(() => watch(env, a!, [b!]))
    expect(env.ctx.watch.subscribe(a!, [b!], () => {}, () => {})).toBeNull()
    subs[0]!.sub.close()
    const again = watch(env, a!, [b!])
    env.ctx.watch.kick(a!)
    expect([subs[1]!.state.kicked, subs[2]!.state.kicked, again.state.kicked]).toEqual([true, true, true])
    expect(subs[0]!.state.kicked).toBe(false)
  })
})

describe('skins via Mojang (cached)', () => {
  const STEVE = '0'.repeat(31) + '1'
  function account(env: TestEnv) {
    env.mojang.accounts.set('steve', {
      uuid: STEVE, name: 'Steve', model: 'slim', skinUrl: 'https://textures.minecraft.net/texture/abc', capeUrl: null,
    })
  }

  it('looks up by name and uuid, caches, invalidates on skin change', async () => {
    const env = makeEnv()
    account(env)
    const r = await env.ctx.skins.byName('STEVE')
    expect(r).toEqual({ uuid: STEVE, name: 'Steve', model: 'slim', textureUrl: 'https://textures.minecraft.net/texture/abc', capeUrl: null })
    const calls = env.mojang.profileCalls
    await env.ctx.skins.byName('steve')
    await env.ctx.skins.byUuid(STEVE)
    expect(env.mojang.profileCalls).toBe(calls) // alles aus dem Cache
    env.ctx.skins.invalidate(STEVE)
    await env.ctx.skins.byUuid(STEVE)
    expect(env.mojang.profileCalls).toBe(calls + 1)
    env.clock.advance(3 * 60_000)
    await env.ctx.skins.byName('steve') // Name noch im Cache (10 min), Profil abgelaufen
    expect(env.mojang.profileCalls).toBe(calls + 2)
  })

  it('unknown names → player_not_found (cached), outages → upstream_unavailable', async () => {
    const env = makeEnv()
    await expect(env.ctx.skins.byName('Nobody')).rejects.toMatchObject({ code: 'player_not_found' })
    const calls = env.mojang.profileCalls
    await expect(env.ctx.skins.byName('nobody')).rejects.toMatchObject({ code: 'player_not_found' })
    expect(env.mojang.profileCalls).toBe(calls)
    env.mojang.fail = true
    await expect(env.ctx.skins.byName('Other')).rejects.toMatchObject({ code: 'upstream_unavailable', status: 502 })
  })

  it('has a global outgoing limit', async () => {
    const env = makeEnv()
    for (let i = 0; i < 100; i++) await env.ctx.skins.byName(`n${i}`).catch(() => {})
    await expect(env.ctx.skins.byName('onemore')).rejects.toMatchObject({ code: 'rate_limited' })
  })

  it('parses the Mojang textures property and only trusts textures.minecraft.net', async () => {
    const tex = (textures: object) => Buffer.from(JSON.stringify({ timestamp: 1, profileId: STEVE, profileName: 'Steve', textures })).toString('base64')
    const profile = (textures: object) => (async () =>
      new Response(JSON.stringify({ id: STEVE, name: 'Steve', properties: [{ name: 'textures', value: tex(textures) }] }), { status: 200 })) as unknown as typeof fetch
    const c1 = createMojangClient('https://s.test', profile({
      SKIN: { url: 'http://textures.minecraft.net/texture/1a2b', metadata: { model: 'slim' } },
      CAPE: { url: 'http://textures.minecraft.net/texture/ffee' },
    }))
    expect(await c1.skinProfile(STEVE)).toEqual({
      uuid: STEVE, name: 'Steve', model: 'slim',
      skinUrl: 'https://textures.minecraft.net/texture/1a2b', capeUrl: 'https://textures.minecraft.net/texture/ffee',
    })
    const c2 = createMojangClient('https://s.test', profile({ SKIN: { url: 'https://evil.example/texture/1a2b' } }))
    expect(await c2.skinProfile(STEVE)).toMatchObject({ model: 'classic', skinUrl: null })
    const none = createMojangClient('https://s.test', (async () => new Response(null, { status: 204 })) as unknown as typeof fetch)
    expect(await none.skinProfile(STEVE)).toBeNull()
    expect(await none.profileByName('x')).toBeNull()
    let seen = ''
    const byName = createMojangClient('https://s.test', (async (u: URL) => {
      seen = String(u)
      return new Response(JSON.stringify({ id: STEVE, name: 'Steve' }), { status: 200 })
    }) as unknown as typeof fetch, 'https://api.test')
    expect(await byName.profileByName('Steve')).toEqual({ uuid: STEVE, name: 'Steve' })
    expect(seen).toBe('https://api.test/users/profiles/minecraft/Steve')
  })
})

describe('request schemas (cosmetics)', () => {
  it('equip body: strict, at least one slot, null allowed', () => {
    expect(parseWith(equipBody, { hat: 'x', aura: null })).toEqual({ hat: 'x', aura: null })
    expect(code(() => parseWith(equipBody, {}))).toBe('invalid_request')
    expect(code(() => parseWith(equipBody, { cape: 'x' }))).toBe('invalid_request')
    expect(code(() => parseWith(equipBody, { hat: 'Bad Id' }))).toBe('invalid_request')
  })

  it('player stream query: 1–200 UUIDs, dashes ok, duplicates collapse', () => {
    const u = 'b0b0b0b0-b0b0-b0b0-b0b0-b0b0b0b0b0b0'
    expect(parseWith(playerStreamQuery, { uuids: `${u},${u.replaceAll('-', '')}` }).uuids).toEqual(['b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0'])
    const many = Array.from({ length: 201 }, (_, i) => i.toString(16).padStart(32, '0')).join(',')
    expect(code(() => parseWith(playerStreamQuery, { uuids: many }))).toBe('invalid_request')
    expect(code(() => parseWith(playerStreamQuery, { uuids: 'nope' }))).toBe('invalid_request')
    expect(code(() => parseWith(playerStreamQuery, {}))).toBe('invalid_request')
  })

  it('codes: exactly one of capeId or cosmeticId', () => {
    expect(parseWith(createCodesBody, { cosmeticId: 'tanzen' })).toMatchObject({ cosmeticId: 'tanzen', count: 1, maxUses: 1 })
    expect(code(() => parseWith(createCodesBody, { capeId: 'team', cosmeticId: 'tanzen' }))).toBe('invalid_request')
    expect(code(() => parseWith(createCodesBody, { count: 2 }))).toBe('invalid_request')
  })
})

describe('migration 2', () => {
  it('keeps existing codes and redemptions when codes gets cosmetic_id', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('PRAGMA foreign_keys = ON')
    db.exec('CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)')
    db.exec(MIGRATIONS[0]!.sql)
    db.exec('INSERT INTO schema_migrations VALUES (1, 0)')
    run(db, "INSERT INTO users (uuid, name, name_lower, created_at, last_login_at) VALUES (?, 'A', 'a', 0, 0)", 'a'.repeat(32))
    db.exec("INSERT INTO capes (id, kind, name, status, unlock, sha256, width, height, created_at) VALUES ('team', 'builtin', 'Team', 'approved', 'admin', 'x', 64, 32, 0)")
    db.exec("INSERT INTO codes (code_hash, hint, cape_id, max_uses, created_at, created_by) VALUES ('h1', 'AAAA', 'team', 1, 0, 'api-key')")
    db.exec("INSERT INTO codes (code_hash, hint, cape_id, max_uses, created_at, created_by) VALUES ('h2', 'BBBB', 'team', 5, 0, 'api-key')")
    run(db, 'INSERT INTO code_redemptions (code_id, uuid, redeemed_at) VALUES (2, ?, 7)', 'a'.repeat(32))

    expect(migrate(db)).toBe(MIGRATIONS.length - 1)
    expect(all(db, 'SELECT id, cape_id, cosmetic_id, hint FROM codes ORDER BY id')).toEqual([
      { id: 1, cape_id: 'team', cosmetic_id: null, hint: 'AAAA' },
      { id: 2, cape_id: 'team', cosmetic_id: null, hint: 'BBBB' },
    ])
    expect(all(db, 'SELECT * FROM code_redemptions')).toEqual([{ code_id: 2, uuid: 'a'.repeat(32), redeemed_at: 7 }])
    expect(all(db, 'PRAGMA foreign_key_check')).toEqual([])
    // AUTOINCREMENT läuft weiter, FK auf codes greift weiter (ON DELETE CASCADE)
    db.exec("INSERT INTO codes (code_hash, hint, cape_id, max_uses, created_at, created_by) VALUES ('h3', 'CCCC', 'team', 1, 0, 'x')")
    expect(one<{ id: number }>(db, "SELECT id FROM codes WHERE code_hash = 'h3'")!.id).toBe(3)
    db.exec('DELETE FROM codes WHERE id = 2')
    expect(all(db, 'SELECT * FROM code_redemptions')).toEqual([])
    expect(() => db.exec("INSERT INTO codes (code_hash, hint, max_uses, created_at, created_by) VALUES ('h4', 'D', 1, 0, 'x')")).toThrow()
    expect(one<{ show_cosmetics: number }>(db, 'SELECT show_cosmetics FROM users')!.show_cosmetics).toBe(1)
  })
})
