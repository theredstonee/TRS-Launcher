import { DatabaseSync } from 'node:sqlite'
import { describe, expect, it } from 'vitest'
import { recordHeartbeat } from '../server/lib/achievements'
import { listAudit } from '../server/lib/audit'
import { redeemCode } from '../server/lib/codes'
import { buildV2Cosmetic } from '../server/lib/cosmetics-v2'
import { seedBuiltinCosmetics, type AnyBuiltinCosmetic } from '../server/lib/cosmetics'
import { adminAssignPet, createPetCodes, creatorPetState } from '../server/lib/creator-pets'
import {
  adminCreatorStats,
  adminCreatorStatsDetail,
  clearCreatorStatsCache,
  snapshotCreatorActivity,
  STATS_CACHE_MS,
} from '../server/lib/creator-stats'
import {
  adminCreateCreator,
  answerHelperInvite,
  createExtraCode,
  createRequest,
  creatorDashboard,
  decideQueueItem,
  endSupport,
  inviteHelper,
  redeemCreatorCode,
  removeHelper,
  updateHelper,
  uploadFanCape,
} from '../server/lib/creators'
import { one, run } from '../server/lib/db'
import { migrateCreatorStats } from '../server/lib/migrations'
import { ownerStaff, setMemberRoles } from '../server/lib/team'
import { normalizeRedeemCode } from '../server/lib/ids'
import { getUser } from '../server/lib/users'
import statsDetailRoute from '../server/routes/v1/admin/creators/[uuid]/stats.get'
import statsListRoute from '../server/routes/v1/admin/creators/stats.get'
import { befriend, code, listen } from './chathelpers'
import { callRoute } from './circuithelpers'
import { ADMIN, login, makeEnv, solidPng, type TestEnv } from './helpers'

// Creator-Statistik für das Team (§35.14), Team-Modus im Dashboard (§35.9) und Fan-Umhang-Anteil. Nur synthetische Daten.

const OWNER = ownerStaff(ADMIN)
const DAY = 86_400_000
const HOUR = 3_600_000

async function player(env: TestEnv, name: string) {
  return (await login(env, name)).user.uuid
}

async function creator(env: TestEnv, name: string, tier: 'starter' | 'partner' | 'top' = 'starter') {
  const l = await login(env, name)
  const c = adminCreateCreator(env.ctx, OWNER, { player: name, tier }).creator
  return { uuid: l.user.uuid, token: l.token, code: c.mainCode! }
}

/** Konto „alt“ machen (vor X Tagen angelegt) – dann zählt es nicht als neuer Spieler. */
function ageAccount(env: TestEnv, uuid: string, days: number) {
  run(env.ctx.db, 'UPDATE users SET created_at = ? WHERE uuid = ?', env.clock.t - days * DAY, uuid)
}

function pet(id: string): AnyBuiltinCosmetic {
  const card = solidPng(8, 8)
  const model = {
    format: 2, id, name: id, slot: 'pet', attach: 'ground',
    texture: { file: `${id}.png`, width: 16, height: 16, scale: 1 },
    bones: [{ id: 'root', pivot: [0, 0, 0] }],
    cubes: [{ bone: 'root', from: [-1, 0, -1], to: [1, 2, 1], faces: { south: { uv: [0, 0, 2, 2] } } }],
  }
  return buildV2Cosmetic({
    id, name: 'Testtier', unlock: 'code', sort: 0, frames: 1, frameTimeMs: null, glowFrames: 0, glowFrameTimeMs: null,
    files: { modelJson: Buffer.from(JSON.stringify(model)), texture: solidPng(16, 16), glow: null, card, cardNight: card },
  })
}

const row = (env: TestEnv, uuid: string, query: Record<string, unknown> = {}) => {
  clearCreatorStatsCache(env.ctx)
  return adminCreatorStats(env.ctx, query).creators.find((r) => r.uuid === uuid)!
}

describe('migration 36', () => {
  it('is idempotent and adds the columns', () => {
    const env = makeEnv()
    migrateCreatorStats(env.ctx.db as unknown as DatabaseSync)
    migrateCreatorStats(env.ctx.db as unknown as DatabaseSync)
    const cols = (t: string) => (env.ctx.db.prepare(`PRAGMA table_info(${t})`).all() as { name: string }[]).map((c) => c.name)
    expect(cols('achievement_playtime')).toContain('last_played_day')
    expect(cols('creator_daily')).toEqual(expect.arrayContaining(['active_fans', 'playing_fans']))
  })
})

describe('creator stats ranking (§35.14)', () => {
  it('counts fans, new fans, new players, pet codes and fan activity per creator', async () => {
    const env = makeEnv()
    seedBuiltinCosmetics(env.ctx, [pet('test_pet')])
    const a = await creator(env, 'Alpha', 'partner')
    const b = await creator(env, 'Beta')
    adminAssignPet(env.ctx, OWNER, a.uuid, { cosmeticId: 'test_pet' })

    // 10 Tage zuvor: zwei alte Fans für Alpha.
    const old1 = await player(env, 'Old1')
    const old2 = await player(env, 'Old2')
    ageAccount(env, old1, 100)
    ageAccount(env, old2, 100)
    redeemCreatorCode(env.ctx, old1, { code: a.code })
    redeemCreatorCode(env.ctx, old2, { code: a.code })
    env.clock.advance(10 * DAY)
    // Heute: ein brandneues Konto (neuer Spieler) + ein 3 Tage altes Konto (kein neuer Spieler) für Alpha, einer für Beta.
    const fresh = await player(env, 'Fresh')
    const notNew = await player(env, 'NotNew')
    ageAccount(env, notNew, 3)
    env.clock.advance(2 * HOUR)
    redeemCreatorCode(env.ctx, fresh, { code: a.code })
    redeemCreatorCode(env.ctx, notNew, { code: a.code })
    const bFan = await player(env, 'BFan')
    redeemCreatorCode(env.ctx, bFan, { code: b.code })

    // Haustier-Codes: 2 Codes à 3, einer davon eingelöst.
    const codes = createPetCodes(env.ctx, a.uuid, { count: 2, maxUses: 3 }).codes
    redeemCode(env.ctx, bFan, normalizeRedeemCode(codes[0]!.code)!)

    // Aktivität: fresh spielt heute, old1 war vor 10 Tagen online (zählt nur in 30 Tagen), old2 nie.
    recordHeartbeat(env.ctx, fresh, true, true)
    recordHeartbeat(env.ctx, notNew, false, true)
    run(
      env.ctx.db,
      'INSERT INTO achievement_playtime (uuid, last_day, last_played_day) VALUES (?, ?, ?)',
      old1, Math.floor(env.clock.t / DAY) - 10, Math.floor(env.clock.t / DAY) - 10,
    )

    const r = row(env, a.uuid)
    expect(r).toMatchObject({
      name: 'Alpha',
      tier: 'partner',
      state: 'active',
      mainCode: a.code,
      fans: { total: 4, today: 2, d7: 2, d30: 4 },
      newPlayers: { total: 1, d7: 1, d30: 1 },
      activity: { online7d: 2, online30d: 3, played7d: 1, played30d: 2 },
    })
    expect(r.pets).toMatchObject({ pet: { cosmeticId: 'test_pet', name: 'Testtier' }, codes: 2, activeCodes: 2, redemptions: 1 })
    expect(r.pets.month).toMatchObject({ used: 6, limit: 50, redemptions: 1 })
    expect(row(env, b.uuid)).toMatchObject({ fans: { total: 1, today: 1 }, newPlayers: { total: 1 }, pets: { pet: null, codes: 0 } })

    // Sortieren, Filtern, Suche, Summen.
    clearCreatorStatsCache(env.ctx)
    const list = adminCreatorStats(env.ctx, { sort: 'name', order: 'asc' })
    expect(list.creators.map((c) => c.name)).toEqual(['Alpha', 'Beta'])
    expect(list.totals).toMatchObject({ creators: 2, fans: 5, newPlayers: 2, petRedemptions: 1 })
    expect(list.definitions).toEqual({ newPlayerWindowHours: 24, activitySince: null })
    expect(adminCreatorStats(env.ctx, { sort: 'fans' }).creators[0]!.name).toBe('Alpha')
    expect(adminCreatorStats(env.ctx, { sort: 'fans', order: 'asc' }).creators[0]!.name).toBe('Beta')
    expect(adminCreatorStats(env.ctx, { tier: 'starter' }).creators.map((c) => c.name)).toEqual(['Beta'])
    expect(adminCreatorStats(env.ctx, { q: 'alp' }).creators.map((c) => c.name)).toEqual(['Alpha'])
    expect(adminCreatorStats(env.ctx, { q: b.code.slice(0, 4).toLowerCase() }).creators.map((c) => c.name)).toContain('Beta')
    expect(adminCreatorStats(env.ctx, { state: 'revoked' }).creators).toEqual([])
    expect(adminCreatorStats(env.ctx, { limit: '1' }).creators).toHaveLength(1)
  })

  it('validates the query strictly', () => {
    const env = makeEnv()
    expect(code(() => adminCreatorStats(env.ctx, { sort: 'drop table' }))).toBe('invalid_request')
    expect(code(() => adminCreatorStats(env.ctx, { tier: 'gold' }))).toBe('invalid_request')
    expect(code(() => adminCreatorStats(env.ctx, { limit: '0' }))).toBe('invalid_request')
    expect(code(() => adminCreatorStats(env.ctx, { q: 'x'.repeat(33) }))).toBe('invalid_request')
    expect(code(() => adminCreatorStats(env.ctx, { foo: '1' }))).toBe('invalid_request')
    expect(code(() => adminCreatorStatsDetail(env.ctx, 'a'.repeat(32), { days: '400' }))).toBe('invalid_request')
  })

  it('caches the ranking briefly', async () => {
    const env = makeEnv()
    const a = await creator(env, 'Alpha')
    clearCreatorStatsCache(env.ctx)
    expect(adminCreatorStats(env.ctx, {}).creators[0]!.fans.total).toBe(0)
    redeemCreatorCode(env.ctx, await player(env, 'Fan'), { code: a.code })
    expect(adminCreatorStats(env.ctx, {}).creators[0]!.fans.total).toBe(0)
    env.clock.advance(STATS_CACHE_MS)
    expect(adminCreatorStats(env.ctx, {}).creators[0]!.fans.total).toBe(1)
  })
})

describe('creator stats detail (§35.14)', () => {
  it('returns a daily series with new fans, new players, pet redemptions and active fans from the snapshots on', async () => {
    const env = makeEnv()
    seedBuiltinCosmetics(env.ctx, [pet('test_pet')])
    const a = await creator(env, 'Alpha', 'top')
    adminAssignPet(env.ctx, OWNER, a.uuid, { cosmeticId: 'test_pet' })
    const f1 = await player(env, 'F1')
    redeemCreatorCode(env.ctx, f1, { code: a.code })
    env.clock.advance(5 * DAY)
    const f2 = await player(env, 'F2')
    ageAccount(env, f2, 10)
    redeemCreatorCode(env.ctx, f2, { code: a.code })
    const [k] = createPetCodes(env.ctx, a.uuid, { count: 1, maxUses: 5 }).codes
    redeemCode(env.ctx, f2, normalizeRedeemCode(k!.code)!)

    clearCreatorStatsCache(env.ctx)
    let d = adminCreatorStatsDetail(env.ctx, a.uuid, {})
    expect(d.series).toHaveLength(90)
    const today = new Date(env.clock.t).toISOString().slice(0, 10)
    const fiveAgo = new Date(env.clock.t - 5 * DAY).toISOString().slice(0, 10)
    expect(d.series.at(-1)!.day).toBe(today)
    expect(d.series.find((s) => s.day === fiveAgo)).toMatchObject({ newFans: 1, newPlayers: 1, redemptions: 1, activeFans: null })
    expect(d.series.at(-1)).toMatchObject({ newFans: 1, newPlayers: 0, redemptions: 1, petRedemptions: 1, activeFans: null, playingFans: null })
    expect(d.definitions.activitySince).toBeNull()

    // Schnappschuss: f1 online, f2 im Spiel → 2 aktiv, 1 spielend. Danach geht f2 offline → Tageswert bleibt (MAX).
    recordHeartbeat(env.ctx, f1, false, true)
    recordHeartbeat(env.ctx, f2, true, true)
    expect(snapshotCreatorActivity(env.ctx)).toBe(1)
    run(env.ctx.db, 'UPDATE achievement_playtime SET last_day = last_day - 1, last_played_day = last_played_day - 1 WHERE uuid = ?', f2)
    snapshotCreatorActivity(env.ctx)
    clearCreatorStatsCache(env.ctx)
    d = adminCreatorStatsDetail(env.ctx, a.uuid, { days: '7' })
    expect(d.series).toHaveLength(7)
    expect(d.series.at(-1)).toMatchObject({ activeFans: 2, playingFans: 1 })
    expect(d.series.at(-2)!.activeFans).toBeNull()
    expect(d.definitions.activitySince).toBe(today)

    // Nächster Tag ohne Aktivität → 0 statt null.
    env.clock.advance(DAY)
    snapshotCreatorActivity(env.ctx)
    clearCreatorStatsCache(env.ctx)
    expect(adminCreatorStatsDetail(env.ctx, a.uuid, { days: '7' }).series.at(-1)).toMatchObject({ activeFans: 0, playingFans: 0 })
    expect(code(() => adminCreatorStatsDetail(env.ctx, 'b'.repeat(32), {}))).toBe('creator_not_found')
  })

  it('counts fan-cape holders among current fans only (share never above 100 %)', async () => {
    const env = makeEnv()
    const a = await creator(env, 'Alpha', 'partner')
    const cape = uploadFanCape(env.ctx, a.uuid, solidPng(64, 32), { name: 'Drache' }).fanCape
    decideQueueItem(env.ctx, OWNER, `c${cape.capeId}`, true)
    const fans = [await player(env, 'F1'), await player(env, 'F2'), await player(env, 'F3')]
    for (const f of fans) redeemCreatorCode(env.ctx, f, { code: a.code })
    endSupport(env.ctx, fans[0]!)
    endSupport(env.ctx, fans[1]!)
    const s = creatorDashboard(env.ctx, a.uuid).stats!
    expect(s).toMatchObject({ activeFans: 1, fanCapeHolders: 3, fanCapeFans: 1 })
    clearCreatorStatsCache(env.ctx)
    expect(adminCreatorStatsDetail(env.ctx, a.uuid, {}).fanCape).toEqual({ holders: 3, fanHolders: 1 })
  })
})

describe('stats routes', () => {
  it('need creators.manage', async () => {
    const env = makeEnv()
    const a = await creator(env, 'Alpha')
    const mod = await login(env, 'Mod')
    setMemberRoles(env.ctx, OWNER, mod.user.uuid, ['moderator'])
    const content = await login(env, 'Content')
    setMemberRoles(env.ctx, OWNER, content.user.uuid, ['content'])
    const auth = (token: string) => ({ authorization: `Bearer ${token}` })
    expect((await callRoute(env, statsListRoute, { url: '/v1/admin/creators/stats', headers: auth(mod.token) })).status).toBe(403)
    const ok = await callRoute(env, statsListRoute, { url: '/v1/admin/creators/stats?sort=name&order=asc', headers: auth(content.token) })
    expect(ok.status).toBe(200)
    expect((ok.body as { creators: { name: string }[] }).creators.map((c) => c.name)).toEqual(['Alpha'])
    const bad = await callRoute(env, statsListRoute, { url: '/v1/admin/creators/stats?sort=nope', headers: auth(content.token) })
    expect(bad.status).toBe(400)
    const detail = await callRoute(env, statsDetailRoute, {
      url: `/v1/admin/creators/${a.uuid}/stats?days=30`, params: { uuid: a.uuid }, headers: auth(content.token),
    })
    expect(detail.status).toBe(200)
    expect((detail.body as { series: unknown[] }).series).toHaveLength(30)
    expect((await callRoute(env, statsDetailRoute, { url: `/v1/admin/creators/${a.uuid}/stats`, params: { uuid: a.uuid }, headers: auth(mod.token) })).status).toBe(403)
  })
})

describe('team mode in the creator dashboard (§35.9)', () => {
  it('team members with creators.manage act with all rights; every change is logged and shown to the creator', async () => {
    const env = makeEnv()
    seedBuiltinCosmetics(env.ctx, [pet('test_pet')])
    const c = await creator(env, 'PixelCST', 'partner')
    adminAssignPet(env.ctx, OWNER, c.uuid, { cosmeticId: 'test_pet' })
    const owner = await login(env, 'Theredstonee', ADMIN)
    // Owner ist zusätzlich Helfer mit NUR „Statistik“ – der Team-Modus gilt trotzdem voll.
    befriend(env, getUser(env.ctx, c.uuid)!, owner.user)
    inviteHelper(env.ctx, c.uuid, { uuid: ADMIN, rights: ['stats'] })
    answerHelperInvite(env.ctx, ADMIN, c.uuid, { accept: true })
    const events = listen(env, c.uuid)

    const dash = creatorDashboard(env.ctx, ADMIN, c.uuid)
    expect(dash.viewer).toEqual({ helper: false, team: true, rights: ['stats', 'codes', 'uploads', 'packs'] })
    expect(dash.stats).not.toBeNull()
    expect(dash.helpers?.map((h) => h.uuid)).toEqual([ADMIN])

    createExtraCode(env.ctx, ADMIN, { code: 'PIXELSTREAM' }, c.uuid)
    createRequest(env.ctx, ADMIN, { kind: 'upgrade', channelUrl: 'https://youtube.com/@pixel', followers: 20000 }, c.uuid)
    createPetCodes(env.ctx, ADMIN, { count: 1, maxUses: 2 }, c.uuid)
    expect(creatorPetState(env.ctx, ADMIN, c.uuid).codes).toHaveLength(1)
    updateHelper(env.ctx, ADMIN, ADMIN, { rights: ['stats', 'codes'] }, c.uuid)

    const log = listAudit(env.ctx, { limit: 50 }).entries.filter((e) => e.action.startsWith('creator.team.'))
    expect(log.map((e) => e.action).sort()).toEqual(['creator.team.code.create', 'creator.team.helper.update', 'creator.team.pet.code.create', 'creator.team.request.create'])
    expect(log.every((e) => e.actor === ADMIN && e.target === c.uuid)).toBe(true)
    expect(events.of('creator_updated').filter((e) => e.kind === 'team_changed').map((e) => e.action).sort())
      .toEqual(['code.create', 'helper.update', 'pet.code.create', 'request.create'])
    const own = creatorDashboard(env.ctx, c.uuid)
    expect(own.teamChanges.map((x) => x.action)).toEqual(expect.arrayContaining(['code.create', 'request.create']))
    expect(JSON.stringify(own.teamChanges)).not.toContain(ADMIN)
    expect(own.requests[0]).toMatchObject({ kind: 'upgrade', status: 'open' })
    expect(own.codes.map((k) => k.code)).toContain('PIXELSTREAM')

    // Helfer einladen bleibt beim Creator; entfernen geht im Team-Modus.
    const friend = await login(env, 'Friend')
    befriend(env, getUser(env.ctx, c.uuid)!, friend.user)
    expect(code(() => inviteHelper(env.ctx, ADMIN, { uuid: friend.user.uuid, rights: ['stats'] }, c.uuid))).toBe('creator_team_forbidden')
    removeHelper(env.ctx, ADMIN, ADMIN, c.uuid)
    expect(one(env.ctx.db, 'SELECT 1 AS x FROM creator_helpers WHERE creator_uuid = ?', c.uuid)).toBeUndefined()
  })

  it('without creators.manage only the normal helper rights apply', async () => {
    const env = makeEnv()
    const c = await creator(env, 'PixelCST', 'partner')
    const mod = await login(env, 'Mod')
    setMemberRoles(env.ctx, OWNER, mod.user.uuid, ['moderator'])
    expect(code(() => creatorDashboard(env.ctx, mod.user.uuid, c.uuid))).toBe('creator_helper_right')
    befriend(env, getUser(env.ctx, c.uuid)!, mod.user)
    inviteHelper(env.ctx, c.uuid, { uuid: mod.user.uuid, rights: ['stats'] })
    answerHelperInvite(env.ctx, mod.user.uuid, c.uuid, { accept: true })
    expect(creatorDashboard(env.ctx, mod.user.uuid, c.uuid).viewer).toEqual({ helper: true, team: false, rights: ['stats'] })
    expect(code(() => createExtraCode(env.ctx, mod.user.uuid, { code: 'MODCODE1' }, c.uuid))).toBe('creator_helper_right')
    expect(code(() => updateHelper(env.ctx, mod.user.uuid, mod.user.uuid, { rights: ['stats', 'codes'] }, c.uuid))).toBe('creator_helper_right')
    expect(code(() => removeHelper(env.ctx, mod.user.uuid, mod.user.uuid, c.uuid))).toBe('creator_helper_right')
    expect(code(() => inviteHelper(env.ctx, mod.user.uuid, { uuid: c.uuid, rights: ['stats'] }, c.uuid))).toBe('creator_helper_right')
    // Kein Creator → Team-Modus 403 creator_inactive.
    const nobody = await player(env, 'Nobody')
    expect(code(() => creatorDashboard(env.ctx, ADMIN, nobody))).toBe('creator_inactive')
  })
})
