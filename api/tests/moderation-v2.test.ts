import { DatabaseSync } from 'node:sqlite'
import { describe, expect, it } from 'vitest'
import { bulkReviewCapes, listAudit, stats, userInfo } from '../server/lib/admin'
import { authenticate, authenticateStaff, verifyLogin, createChallenge } from '../server/lib/auth'
import { uploadCape } from '../server/lib/capes'
import { addMembers, createGroup, openDm, sendMessage } from '../server/lib/chat'
import { uploadCosmetic } from '../server/lib/cosmetics'
import { dashboard, search } from '../server/lib/dashboard'
import { all, migrate, one, run } from '../server/lib/db'
import { addPlayerNote, deletePlayerNote, listPlayers, playerFile } from '../server/lib/dossier'
import { acceptRequest, sendRequest } from '../server/lib/friends'
import { createRoom, invite, join, myRooms, type RoomSettings } from '../server/lib/hosting'
import { lookupPlayers } from '../server/lib/lookup'
import { MIGRATIONS, migrateModerationV2 } from '../server/lib/migrations'
import { adminBulkReports, adminListReports, adminReportAction, createReport, myModeration } from '../server/lib/moderation'
import { updatePresence } from '../server/lib/playerevents'
import {
  SYSTEM,
  activeSanction,
  adminSanctionView,
  changeDuration,
  createAppeal,
  createSanction,
  decideAppeal,
  listAppeals,
  listSanctions,
  liftSanction,
  mySanctions,
  sweepSanctions,
  type SanctionInput,
  type Staff,
} from '../server/lib/sanctions'
import { appealBody, adminSanctionBody } from '../server/lib/schemas'
import { removeRole, setRole, listRoles } from '../server/lib/staff'
import { getUser, isBanned, staffRole, type UserRow } from '../server/lib/users'
import { approveWebLogin, pollWebLogin, startWebLogin, webSession } from '../server/lib/weblogin'
import { befriend, code, listen, players } from './chathelpers'
import { ADMIN, login, makeEnv, seedCosmeticFixtures, solidPng, templatePng, type TestEnv } from './helpers'

const HOUR = 60
const DAY = 1440
const HOSTING_ENV = { RELAY_SECRET: 'relay-secret-0123456789abcdef-0123456789abcdef', RELAY_HOST: 'relay.example.test' }
const SETTINGS: RoomSettings = {
  name: 'Welt', mcVersion: '1.21.4', loader: 'fabric', maxPlayers: 4, gameMode: 'survival',
  pvp: true, cheats: false, open: true, visibility: 'friends',
}
const ADMIN_STAFF: Staff = { uuid: ADMIN, role: 'admin' }

/** Team + Spieler: Admin (ADMIN_UUIDS), Moderator, zweiter Moderator, Spieler. */
async function team(env: TestEnv, ...names: string[]) {
  await login(env, 'Admin', ADMIN)
  const [mod, mod2, ...rest] = await players(env, 'Mod', 'Mod2', ...names)
  setRole(env.ctx, ADMIN_STAFF, mod!.uuid, 'moderator')
  setRole(env.ctx, ADMIN_STAFF, mod2!.uuid, 'moderator')
  const modStaff: Staff = { uuid: mod!.uuid, role: 'moderator' }
  const mod2Staff: Staff = { uuid: mod2!.uuid, role: 'moderator' }
  return { mod: mod!, mod2: mod2!, modStaff, mod2Staff, players: rest }
}

const input = (uuid: string, kind: SanctionInput['kind'], minutes: number | null, extra: Partial<SanctionInput> = {}): SanctionInput =>
  ({ uuid, kind, minutes, reasonCode: 'spam', reason: 'Test', ...extra })

const fresh = (env: TestEnv, u: UserRow) => getUser(env.ctx, u.uuid)!

describe('roles', () => {
  it('ADMIN_UUIDS are fixed admins; admins grant and remove roles (audited); nobody changes their own', async () => {
    const env = makeEnv()
    const { mod, modStaff, players: [p] } = await team(env, 'Player')
    expect(staffRole(env.ctx, ADMIN)).toBe('admin')
    expect(staffRole(env.ctx, mod.uuid)).toBe('moderator')
    expect(code(() => setRole(env.ctx, ADMIN_STAFF, ADMIN, 'moderator'))).toBe('role_locked')
    expect(code(() => removeRole(env.ctx, ADMIN_STAFF, ADMIN))).toBe('role_locked')
    expect(code(() => setRole(env.ctx, modStaff, mod.uuid, 'admin'))).toBe('cannot_change_self')
    expect(code(() => setRole(env.ctx, ADMIN_STAFF, 'f'.repeat(32), 'moderator'))).toBe('user_not_found')
    setRole(env.ctx, ADMIN_STAFF, p!.uuid, 'admin', 'Co-Admin')
    expect(listRoles(env.ctx).map((r) => [r.role, r.source])).toEqual([['admin', 'env'], ['admin', 'db'], ['moderator', 'db'], ['moderator', 'db']])
    removeRole(env.ctx, ADMIN_STAFF, p!.uuid)
    expect(staffRole(env.ctx, p!.uuid)).toBeNull()
    expect(code(() => removeRole(env.ctx, ADMIN_STAFF, p!.uuid))).toBe('role_not_found')
    expect(listAudit(env.ctx, { action: 'role.', limit: 10 }).entries.map((e) => e.action)).toEqual(['role.remove', 'role.set', 'role.set', 'role.set'])
    // me.role für Clients.
    expect((await login(env, 'Mod', mod.uuid)).user).toMatchObject({ admin: false, role: 'moderator' })
  })

  it('moderators sign in to the website; losing the role ends the session at once', async () => {
    const env = makeEnv()
    const { mod, players: [p] } = await team(env, 'Player')
    const modAuth = authenticate(env.ctx, `Bearer ${(await login(env, 'Mod', mod.uuid)).token}`)
    const playerAuth = authenticate(env.ctx, `Bearer ${(await login(env, 'Player', p!.uuid)).token}`)
    const s = startWebLogin(env.ctx)
    expect(code(() => approveWebLogin(env.ctx, playerAuth, s.code))).toBe('not_admin')
    approveWebLogin(env.ctx, modAuth, s.code)
    const r = pollWebLogin(env.ctx, s.pollSecret)
    expect(r.status).toBe('approved')
    const token = (r as { token: string }).token
    expect(webSession(env.ctx, token, undefined, false)).toMatchObject({ uuid: mod.uuid, role: 'moderator' })
    // Bearer-Zugriff: Moderator ja, Admin-Bereich nein.
    const bearer = `Bearer ${(await login(env, 'Mod', mod.uuid)).token}`
    expect(authenticateStaff(env.ctx, { authorization: bearer }, 'moderator')).toEqual({ uuid: mod.uuid, role: 'moderator' })
    expect(code(() => authenticateStaff(env.ctx, { authorization: bearer }, 'admin'))).toBe('forbidden')
    expect(authenticateStaff(env.ctx, { adminKey: env.ctx.config.adminApiKey! }, 'admin')).toEqual({ uuid: 'api-key', role: 'admin' })
    removeRole(env.ctx, ADMIN_STAFF, mod.uuid)
    expect(code(() => webSession(env.ctx, token, undefined, false))).toBe('unauthorized')
  })
})

describe('rights matrix', () => {
  it('moderators: temporary sanctions up to 7 days (warnings 30), never permanent, no account bans, no team members', async () => {
    const env = makeEnv()
    const { mod, mod2, modStaff, players: [p] } = await team(env, 'Player')
    const ok = (s: Staff, i: SanctionInput) => createSanction(env.ctx, s, i).id
    expect(ok(modStaff, input(p!.uuid, 'chat_mute', 7 * DAY))).toBeGreaterThan(0)
    expect(ok(modStaff, input(p!.uuid, 'warn', 30 * DAY))).toBeGreaterThan(0)
    for (const kind of ['social_ban', 'upload_ban', 'hosting_ban'] as const) expect(ok(modStaff, input(p!.uuid, kind, HOUR))).toBeGreaterThan(0)
    expect(code(() => createSanction(env.ctx, modStaff, input(p!.uuid, 'chat_mute', 7 * DAY + 1)))).toBe('duration_not_allowed')
    expect(code(() => createSanction(env.ctx, modStaff, input(p!.uuid, 'warn', 30 * DAY + 1)))).toBe('duration_not_allowed')
    expect(code(() => createSanction(env.ctx, modStaff, input(p!.uuid, 'chat_mute', null)))).toBe('duration_not_allowed')
    expect(code(() => createSanction(env.ctx, modStaff, input(p!.uuid, 'account_ban', HOUR)))).toBe('admin_only')
    expect(code(() => createSanction(env.ctx, modStaff, input(mod2.uuid, 'chat_mute', HOUR)))).toBe('cannot_moderate_staff')
    expect(code(() => createSanction(env.ctx, modStaff, input(ADMIN, 'chat_mute', HOUR)))).toBe('cannot_moderate_admin')
    expect(code(() => createSanction(env.ctx, modStaff, input(mod.uuid, 'warn', HOUR)))).toBe('cannot_target_self')
    // Schema: Pflicht-Grund aus Vorlagen.
    expect(adminSanctionBody.safeParse({ uuid: p!.uuid, kind: 'warn', duration: '1d' }).success).toBe(false)
    expect(adminSanctionBody.safeParse({ uuid: p!.uuid, kind: 'warn', duration: '1d', reasonCode: 'made_up' }).success).toBe(false)
    expect(adminSanctionBody.safeParse({ uuid: p!.uuid, kind: 'warn', duration: 'custom', reasonCode: 'spam' }).success).toBe(false)
    expect(adminSanctionBody.safeParse({ uuid: p!.uuid, kind: 'warn', duration: 'custom', minutes: 90, reasonCode: 'spam', note: 'intern' }).success).toBe(true)
  })

  it('admins: permanent and account bans, can sanction moderators but not admins', async () => {
    const env = makeEnv()
    const { mod, players: [p] } = await team(env, 'Player')
    createSanction(env.ctx, ADMIN_STAFF, input(p!.uuid, 'account_ban', null))
    expect(isBanned(env.ctx, p!.uuid)).toBe(true)
    createSanction(env.ctx, ADMIN_STAFF, input(mod.uuid, 'chat_mute', HOUR))
    expect(activeSanction(env.ctx, mod.uuid, 'chat_mute')).toBeDefined()
    expect(code(() => createSanction(env.ctx, ADMIN_STAFF, input(ADMIN, 'warn', HOUR)))).toBe('cannot_moderate_admin')
    expect(code(() => createSanction(env.ctx, { uuid: 'api-key', role: 'admin' }, input(ADMIN, 'warn', HOUR)))).toBe('cannot_moderate_admin')
  })

  it('moderators cannot change or lift sanctions given by admins; admins can change everything', async () => {
    const env = makeEnv()
    const { modStaff, mod2Staff, players: [p] } = await team(env, 'Player')
    const byAdmin = createSanction(env.ctx, ADMIN_STAFF, input(p!.uuid, 'social_ban', DAY))
    const byMod = createSanction(env.ctx, modStaff, input(p!.uuid, 'upload_ban', DAY))
    expect(code(() => liftSanction(env.ctx, modStaff, byAdmin.id, 'nope'))).toBe('admin_only')
    expect(code(() => changeDuration(env.ctx, modStaff, byAdmin.id, env.clock.t + HOUR * 60_000, 'nope'))).toBe('admin_only')
    // Anderer Moderator darf die Strafe eines Moderators ändern – aber nur bis 7 Tage.
    expect(code(() => changeDuration(env.ctx, mod2Staff, byMod.id, env.clock.t + 8 * DAY * 60_000, 'länger'))).toBe('duration_not_allowed')
    expect(code(() => changeDuration(env.ctx, mod2Staff, byMod.id, null, 'dauerhaft'))).toBe('duration_not_allowed')
    changeDuration(env.ctx, mod2Staff, byMod.id, env.clock.t + 2 * DAY * 60_000, 'Wiederholung')
    liftSanction(env.ctx, ADMIN_STAFF, byAdmin.id, 'Fehler')
    expect(code(() => liftSanction(env.ctx, ADMIN_STAFF, byAdmin.id, 'nochmal'))).toBe('sanction_not_active')
  })
})

describe('every sanction kind takes effect', () => {
  it('warning: counted, event with public data only', async () => {
    const env = makeEnv()
    const { modStaff, players: [p] } = await team(env, 'Player')
    const ev = listen(env, p!.uuid)
    createSanction(env.ctx, modStaff, input(p!.uuid, 'warn', 7 * DAY, { reasonCode: 'insult_hate', reason: 'Bitte freundlich bleiben', note: 'intern: dritter Vorfall' }))
    const added = ev.of('sanction_added')[0]!.sanction
    expect(added).toMatchObject({ kind: 'warn', reasonCode: 'insult_hate', reason: 'Bitte freundlich bleiben', status: 'active', appealable: true })
    expect(JSON.stringify(ev.events)).not.toContain('intern')
    expect(JSON.stringify(ev.events)).not.toContain(modStaff.uuid)
    expect(ev.of('moderation')).toEqual([{ type: 'moderation', action: 'warn', reason: 'Bitte freundlich bleiben', until: null }])
    expect(myModeration(env.ctx, p!.uuid).warnings).toHaveLength(1)
  })

  it('chat mute: sending is blocked with chat_muted + sanction details', async () => {
    const env = makeEnv()
    const { modStaff, players: [a, b] } = await team(env, 'Alex', 'Bob')
    befriend(env, a!, b!)
    const dm = openDm(env.ctx, a!.uuid, b!.uuid)
    const s = createSanction(env.ctx, modStaff, input(a!.uuid, 'chat_mute', HOUR))
    try {
      sendMessage(env.ctx, a!.uuid, dm.id, { text: 'hallo' })
      expect.unreachable()
    } catch (e) {
      const err = e as { status: number, code: string, details: { until: string, sanction: Record<string, unknown> } }
      expect(err.code).toBe('chat_muted')
      expect(err.details.until).toBe(new Date(env.clock.t + 3_600_000).toISOString())
      expect(err.details.sanction).toMatchObject({ id: s.id, kind: 'chat_mute', reasonCode: 'spam' })
      expect(err.details.sanction).not.toHaveProperty('note')
    }
    env.clock.advance(3_600_001)
    expect(sendMessage(env.ctx, a!.uuid, dm.id, { text: 'wieder da' }).created).toBe(true)
  })

  it('social ban: no friend requests, groups or invites – plain messages still work', async () => {
    const env = makeEnv()
    const { modStaff, players: [a, b, c] } = await team(env, 'Alex', 'Bob', 'Carl')
    befriend(env, a!, b!)
    const dm = openDm(env.ctx, a!.uuid, b!.uuid)
    const g = createGroup(env.ctx, a!.uuid, 'Crew', [b!.uuid])
    sendRequest(env.ctx, c!, { uuid: a!.uuid })
    createSanction(env.ctx, modStaff, input(a!.uuid, 'social_ban', DAY))
    expect(code(() => sendRequest(env.ctx, fresh(env, a!), { uuid: c!.uuid }))).toBe('sanctioned')
    expect(code(() => acceptRequest(env.ctx, fresh(env, a!), c!.uuid))).toBe('sanctioned')
    expect(code(() => createGroup(env.ctx, a!.uuid, 'Neu', [b!.uuid]))).toBe('sanctioned')
    expect(code(() => addMembers(env.ctx, a!.uuid, g.id, [c!.uuid]))).toBe('sanctioned')
    expect(code(() => sendMessage(env.ctx, a!.uuid, dm.id, { invite: { address: 'play.example.net' } }))).toBe('sanctioned')
    expect(sendMessage(env.ctx, a!.uuid, dm.id, { text: 'normal geht' }).created).toBe(true)
    try {
      sendRequest(env.ctx, fresh(env, a!), { uuid: c!.uuid })
    } catch (e) {
      expect((e as { status: number, details: { sanction: { kind: string } } })).toMatchObject({ status: 403, details: { sanction: { kind: 'social_ban' } } })
    }
  })

  it('upload ban: capes and cosmetics are refused', async () => {
    const env = makeEnv()
    seedCosmeticFixtures(env)
    const { modStaff, players: [a] } = await team(env, 'Alex')
    createSanction(env.ctx, modStaff, input(a!.uuid, 'upload_ban', DAY, { reasonCode: 'copyright' }))
    expect(code(() => uploadCape(env.ctx, a!.uuid, solidPng(64, 32), undefined))).toBe('sanctioned')
    expect(code(() => uploadCosmetic(env.ctx, a!.uuid, templatePng(env, 'crown'), { template: 'crown' }))).toBe('sanctioned')
  })

  it('hosting ban: running world closes, no new worlds, no joining, guests are removed', async () => {
    const env = makeEnv({ env: HOSTING_ENV })
    const { modStaff, players: [host, guest, other] } = await team(env, 'Host', 'Guest', 'Other')
    befriend(env, host!, guest!)
    befriend(env, other!, guest!)
    const room = createRoom(env.ctx, host!, SETTINGS).room
    const otherRoom = createRoom(env.ctx, other!, SETTINGS).room
    invite(env.ctx, fresh(env, other!), otherRoom.id, guest!.uuid, { chat: false })
    join(env.ctx, fresh(env, guest!), { roomId: otherRoom.id })
    const evHost = listen(env, host!.uuid)
    createSanction(env.ctx, modStaff, input(host!.uuid, 'hosting_ban', DAY))
    expect(evHost.of('hosting_room_closed')).toEqual([{ type: 'hosting_room_closed', roomId: room.id, reason: 'host_unavailable' }])
    expect(myRooms(env.ctx, host!.uuid)).toEqual([])
    expect(code(() => createRoom(env.ctx, fresh(env, host!), SETTINGS))).toBe('sanctioned')
    createSanction(env.ctx, modStaff, input(guest!.uuid, 'hosting_ban', DAY))
    expect(one(env.ctx.db, "SELECT 1 AS x FROM hosting_members WHERE uuid = ? AND state = 'accepted'", guest!.uuid)).toBeUndefined()
    expect(code(() => join(env.ctx, fresh(env, guest!), { roomId: otherRoom.id }))).toBe('sanctioned')
  })

  it('account ban: tokens die, login is refused with an appeal token, badge/cape hidden; temporary bans end', async () => {
    const env = makeEnv()
    const { players: [a, viewer] } = await team(env, 'Alex', 'Viewer')
    const token = (await login(env, 'Alex', a!.uuid)).token
    updatePresence(env.ctx, a!.uuid, () => env.ctx.presence.set(a!.uuid, 'in-game', null, 'client'))
    updatePresence(env.ctx, viewer!.uuid, () => env.ctx.presence.set(viewer!.uuid, 'in-game', null, 'client'))
    expect(lookupPlayers(env.ctx, viewer!.uuid, [a!.uuid]).players.map((x) => x.uuid)).toEqual([a!.uuid])

    createSanction(env.ctx, ADMIN_STAFF, input(a!.uuid, 'account_ban', 2 * HOUR, { reasonCode: 'cheating', note: 'geheim' }))
    expect(code(() => authenticate(env.ctx, `Bearer ${token}`))).toBe('unauthorized')
    expect(lookupPlayers(env.ctx, viewer!.uuid, [a!.uuid]).players).toEqual([])
    expect(stats(env.ctx).users.banned).toBe(1)
    expect(userInfo(env.ctx, a!.uuid).banned).toMatchObject({ until: new Date(env.clock.t + 7_200_000).toISOString() })

    // Neu anmelden → 403 banned mit Angaben + Einspruch-Token (nur für die Einspruch-Routen).
    let appealToken = ''
    try {
      await login(env, 'Alex', a!.uuid)
      expect.unreachable()
    } catch (e) {
      const err = e as { code: string, details: { sanction: { kind: string, reasonCode: string }, appealToken: string } }
      expect(err.code).toBe('banned')
      expect(err.details.sanction).toMatchObject({ kind: 'account_ban', reasonCode: 'cheating', appealable: true })
      expect(JSON.stringify(err.details)).not.toContain('geheim')
      appealToken = err.details.appealToken
    }
    expect(code(() => authenticate(env.ctx, `Bearer ${appealToken}`))).toBe('banned')
    const appealAuth = authenticate(env.ctx, `Bearer ${appealToken}`, { appeal: true })
    expect(mySanctions(env.ctx, appealAuth.uuid).active.map((s) => s.kind)).toEqual(['account_ban'])

    // Zeitlich begrenzt: danach wieder frei (Einspruch-Token gilt nicht als Sitzung).
    env.clock.advance(2 * 3_600_000 + 1)
    expect(isBanned(env.ctx, a!.uuid)).toBe(false)
    expect(code(() => authenticate(env.ctx, `Bearer ${appealToken}`))).toBe('unauthorized')
    const { serverId } = createChallenge(env.ctx)
    env.mojang.join('Alex', a!.uuid, serverId)
    expect((await verifyLogin(env.ctx, 'Alex', serverId)).user.uuid).toBe(a!.uuid)
  })
})

describe('lift, shorten, extend', () => {
  it('keeps every change with who/when/why; lifted and expired stay in the history', async () => {
    const env = makeEnv()
    const { modStaff, players: [p] } = await team(env, 'Player')
    const ev = listen(env, p!.uuid)
    const s = createSanction(env.ctx, modStaff, input(p!.uuid, 'chat_mute', 3 * DAY))
    changeDuration(env.ctx, modStaff, s.id, env.clock.t + DAY * 60_000, 'Einsicht gezeigt')
    changeDuration(env.ctx, ADMIN_STAFF, s.id, env.clock.t + 10 * DAY * 60_000, 'Rückfall')
    expect(code(() => changeDuration(env.ctx, modStaff, s.id, env.clock.t - 1, 'x'))).toBe('invalid_duration')
    liftSanction(env.ctx, ADMIN_STAFF, s.id, 'Irrtum')
    const v = adminSanctionView(env.ctx, env.ctx.db.prepare('SELECT * FROM sanctions WHERE id = ?').get(s.id) as never)
    expect(v.status).toBe('lifted')
    expect(v.changes.map((c) => [c.action, c.reason, c.actor.uuid])).toEqual([
      ['shorten', 'Einsicht gezeigt', modStaff.uuid], ['extend', 'Rückfall', ADMIN], ['lift', 'Irrtum', ADMIN],
    ])
    expect(v).toMatchObject({ liftedBy: { uuid: ADMIN, name: 'Admin' }, liftReason: 'Irrtum' })
    expect(ev.of('sanction_updated').map((e) => e.sanction.status)).toEqual(['active', 'active', 'lifted'])
    expect(ev.of('moderation').at(-1)).toMatchObject({ action: 'unmute' })

    const w = createSanction(env.ctx, modStaff, input(p!.uuid, 'warn', HOUR))
    env.clock.advance(3_600_001)
    const list = listSanctions(env.ctx, { status: 'all', uuid: p!.uuid, sort: 'newest', limit: 10 })
    expect(list.sanctions.map((x) => [x.id, x.status])).toEqual([[w.id, 'expired'], [s.id, 'lifted']])
    expect(listSanctions(env.ctx, { status: 'active', sort: 'newest', limit: 10 }).sanctions).toEqual([])
    expect(listSanctions(env.ctx, { status: 'lifted', actor: modStaff.uuid, sort: 'newest', limit: 10 }).sanctions.map((x) => x.id)).toEqual([s.id])
    // Cursor-Seiten.
    const page1 = listSanctions(env.ctx, { status: 'all', sort: 'newest', limit: 1 })
    expect(listSanctions(env.ctx, { status: 'all', sort: 'newest', limit: 1, cursor: page1.nextCursor! }).sanctions.map((x) => x.id)).toEqual([s.id])
    expect(listAudit(env.ctx, { ref: `s${s.id}`, limit: 10 }).entries.map((e) => e.action)).toEqual(['chat.unmute', 'sanction.extend', 'sanction.shorten', 'chat.mute'])
  })
})

describe('appeals', () => {
  it('one appeal per sanction; text 20–1000; decisions lift/shorten/uphold with events; own sanctions are off limits', async () => {
    const env = makeEnv()
    const { mod2Staff, modStaff, players: [p, q] } = await team(env, 'Player', 'Other')
    const ev = listen(env, p!.uuid)
    const s1 = createSanction(env.ctx, modStaff, input(p!.uuid, 'chat_mute', 5 * DAY))
    const s2 = createSanction(env.ctx, modStaff, input(p!.uuid, 'social_ban', 5 * DAY))
    const s3 = createSanction(env.ctx, modStaff, input(p!.uuid, 'upload_ban', 5 * DAY))

    expect(appealBody.safeParse({ text: 'zu kurz' }).success).toBe(false)
    expect(appealBody.safeParse({ text: 'x'.repeat(1001) }).success).toBe(false)
    expect(appealBody.safeParse({ text: 'Das war ein Missverständnis,\nbitte prüfen.' }).success).toBe(true)
    const text = 'Das war ein Missverständnis, bitte noch einmal prüfen.'
    expect(createAppeal(env.ctx, p!.uuid, s1.id, text)).toMatchObject({ appeal: { status: 'open' }, appealable: false })
    expect(code(() => createAppeal(env.ctx, p!.uuid, s1.id, text))).toBe('appeal_exists')
    expect(code(() => createAppeal(env.ctx, q!.uuid, s2.id, text))).toBe('sanction_not_found')
    createAppeal(env.ctx, p!.uuid, s2.id, text)
    createAppeal(env.ctx, p!.uuid, s3.id, text)

    const open = listAppeals(env.ctx, { status: 'open', limit: 10 })
    expect(open.open).toBe(3)
    const [a1, a2, a3] = open.appeals
    expect(a1!.sanction.id).toBe(s1.id)
    expect(code(() => decideAppeal(env.ctx, modStaff, a1!.id, { decision: 'lift', response: 'ok' }))).toBe('own_sanction')

    decideAppeal(env.ctx, mod2Staff, a1!.id, { decision: 'lift', response: 'Du hast recht.' })
    expect(activeSanction(env.ctx, p!.uuid, 'chat_mute')).toBeUndefined()
    decideAppeal(env.ctx, mod2Staff, a2!.id, { decision: 'shorten', response: 'Wir verkürzen.', endsAt: env.clock.t + DAY * 60_000 })
    expect(activeSanction(env.ctx, p!.uuid, 'social_ban')!.expires_at).toBe(env.clock.t + DAY * 60_000)
    decideAppeal(env.ctx, ADMIN_STAFF, a3!.id, { decision: 'uphold', response: 'Die Sperre bleibt.' })
    expect(code(() => decideAppeal(env.ctx, ADMIN_STAFF, a3!.id, { decision: 'lift', response: 'x' }))).toBe('appeal_decided')

    expect(ev.of('appeal_decided').map((e) => [e.sanctionId, e.appeal.status, e.appeal.response])).toEqual([
      [s1.id, 'lifted', 'Du hast recht.'], [s2.id, 'shortened', 'Wir verkürzen.'], [s3.id, 'upheld', 'Die Sperre bleibt.'],
    ])
    const mine = mySanctions(env.ctx, p!.uuid)
    expect(mine.past.map((x) => [x.kind, x.status])).toEqual([['chat_mute', 'lifted']])
    expect(JSON.stringify(mine)).not.toContain(modStaff.uuid)
    expect(listAppeals(env.ctx, { status: 'open', limit: 10 }).open).toBe(0)
    // Abgelaufene Strafen: kein Einspruch mehr.
    const s4 = createSanction(env.ctx, modStaff, input(p!.uuid, 'warn', HOUR))
    env.clock.advance(3_600_001)
    expect(code(() => createAppeal(env.ctx, p!.uuid, s4.id, text))).toBe('sanction_not_active')
  })
})

describe('bulk actions', () => {
  it('reports: dismiss many in one transaction, skip unknown/resolved, limit 50', async () => {
    const env = makeEnv()
    const { modStaff, players: [a, b, c, d] } = await team(env, 'A', 'B', 'C', 'D')
    const ids = [b, c, d].map((u) => createReport(env.ctx, u!.uuid, { kind: 'player', reason: 'spam', uuid: a!.uuid }).id)
    // 3 Melder → Auto-Stumm bis zur Prüfung; Sammel-Abweisen hebt ihn auf.
    expect(activeSanction(env.ctx, a!.uuid, 'chat_mute')).toMatchObject({ auto: 'reports' })
    const r = adminBulkReports(env.ctx, modStaff, [...ids, 'r0000000000000000'], 'dismissed')
    expect(r.updated.sort()).toEqual([...ids].sort())
    expect(r.skipped).toEqual(['r0000000000000000'])
    expect(adminListReports(env.ctx, { status: 'active', limit: 10 }).reports).toEqual([])
    expect(activeSanction(env.ctx, a!.uuid, 'chat_mute')).toBeUndefined()
    expect(adminBulkReports(env.ctx, modStaff, ids, 'actioned').updated).toEqual([])
    expect(code(() => adminBulkReports(env.ctx, modStaff, Array.from({ length: 51 }, (_, i) => `r${i.toString(16).padStart(16, '0')}`), 'dismissed'))).toBe('bulk_too_large')
  })

  it('capes: approve many at once; nothing changes when the transaction fails', async () => {
    const env = makeEnv()
    const { mod, players: [a] } = await team(env, 'Alex')
    const capes = [1, 2, 3].map((i) => uploadCape(env.ctx, a!.uuid, solidPng(64, 32, [i, 2, 3, 255]), undefined).id)
    const r = bulkReviewCapes(env.ctx, mod.uuid, capes.slice(0, 2), 'approve')
    expect(r).toEqual({ updated: capes.slice(0, 2), skipped: [] })
    expect(capes.map((id) => one<{ status: string }>(env.ctx.db, 'SELECT status FROM capes WHERE id = ?', id)!.status)).toEqual(['approved', 'approved', 'pending'])
    // Fehler mitten in der Transaktion (Trigger) → alles bleibt wie vorher.
    run(env.ctx.db, 'CREATE TEMP TABLE fail_ids (id TEXT)')
    run(env.ctx.db, 'INSERT INTO fail_ids (id) VALUES (?)', capes[2]!)
    env.ctx.db.exec("CREATE TEMP TRIGGER fail_reject BEFORE UPDATE ON capes WHEN NEW.id IN (SELECT id FROM fail_ids) BEGIN SELECT RAISE(ABORT, 'boom'); END")
    expect(() => bulkReviewCapes(env.ctx, mod.uuid, [capes[0]!, capes[2]!], 'reject')).toThrow(/boom/)
    expect(capes.map((id) => one<{ status: string }>(env.ctx.db, 'SELECT status FROM capes WHERE id = ?', id)!.status)).toEqual(['approved', 'approved', 'pending'])
  })
})

describe('migration 9', () => {
  it('moves chat_sanctions and bans into sanctions without losing data, is idempotent and drops the old tables', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('PRAGMA foreign_keys = ON')
    db.exec('CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)')
    for (const m of MIGRATIONS.filter((x) => x.version <= 8)) {
      db.exec(m.sql!)
      db.prepare('INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)').run(m.version, 1)
    }
    const u = 'a'.repeat(32)
    const gone = 'b'.repeat(32)
    db.prepare("INSERT INTO users (uuid, name, name_lower, created_at, last_login_at) VALUES (?, 'Troll', 'troll', 1000, 2000)").run(u)
    const ins = db.prepare('INSERT INTO chat_sanctions (uuid, kind, reason, report_id, auto, created_at, created_by, expires_at, lifted_at, lifted_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)')
    ins.run(u, 'warn', 'Achtung', 'r0123456789abcdef', null, 5000, ADMIN, null, null, null)
    ins.run(u, 'mute', 'Spam', null, 'spam', 6000, 'system', 7000, null, null)
    ins.run(u, 'mute', null, null, 'reports', 8000, 'system', null, 9000, ADMIN)
    // Gelöschtes Konto mit aktiver Stummschaltung (ohne users-Zeile).
    ins.run(gone, 'mute', 'bleibt', null, null, 8500, ADMIN, null, null, null)
    db.prepare('INSERT INTO bans (uuid, reason, banned_at, banned_by) VALUES (?, ?, ?, ?)').run(gone, 'Betrug', 4000, 'api-key')

    expect(migrate(db)).toBe(1)
    const rows = all<Record<string, unknown>>(db, 'SELECT uuid, kind, reason_code, reason, report_id, auto, created_at, created_by, created_role, expires_at, lifted_at, lifted_by, legacy_source FROM sanctions ORDER BY created_at')
    expect(rows).toEqual([
      { uuid: gone, kind: 'account_ban', reason_code: 'legacy', reason: 'Betrug', report_id: null, auto: null, created_at: 4000, created_by: 'api-key', created_role: 'admin', expires_at: null, lifted_at: null, lifted_by: null, legacy_source: 'bans' },
      { uuid: u, kind: 'warn', reason_code: 'legacy', reason: 'Achtung', report_id: 'r0123456789abcdef', auto: null, created_at: 5000, created_by: ADMIN, created_role: 'admin', expires_at: 5000 + 90 * 86_400_000, lifted_at: null, lifted_by: null, legacy_source: 'chat_sanctions' },
      { uuid: u, kind: 'chat_mute', reason_code: 'auto_spam', reason: 'Spam', report_id: null, auto: 'spam', created_at: 6000, created_by: 'system', created_role: 'system', expires_at: 7000, lifted_at: null, lifted_by: null, legacy_source: 'chat_sanctions' },
      { uuid: u, kind: 'chat_mute', reason_code: 'auto_reports', reason: null, report_id: null, auto: 'reports', created_at: 8000, created_by: 'system', created_role: 'system', expires_at: null, lifted_at: 9000, lifted_by: ADMIN, legacy_source: 'chat_sanctions' },
      { uuid: gone, kind: 'chat_mute', reason_code: 'legacy', reason: 'bleibt', report_id: null, auto: null, created_at: 8500, created_by: ADMIN, created_role: 'admin', expires_at: null, lifted_at: null, lifted_by: null, legacy_source: 'chat_sanctions' },
    ])
    for (const t of ['chat_sanctions', 'bans']) expect(one(db, "SELECT 1 AS x FROM sqlite_master WHERE name = ?", t)).toBeUndefined()
    expect(all(db, 'SELECT uuid, name FROM name_history')).toEqual([{ uuid: u, name: 'Troll' }])
    expect(all<{ name: string }>(db, "SELECT name FROM pragma_table_info('sessions')").map((c) => c.name)).toContain('scope')

    // Zweiter Lauf (z. B. nach abgebrochenem Deploy): nichts doppelt, kein Fehler.
    migrateModerationV2(db)
    expect(one<{ n: number }>(db, 'SELECT COUNT(*) AS n FROM sanctions')!.n).toBe(5)
    expect(migrate(db)).toBe(0)
    run(db, 'DELETE FROM sanctions')
    db.close()
  })
})

describe('player file, dashboard, search, retention', () => {
  it('collects everything about a player; notes; search finds names, uuids, report ids and capes', async () => {
    const env = makeEnv({ env: HOSTING_ENV })
    const { mod, modStaff, mod2Staff, players: [a, b] } = await team(env, 'Alex', 'Bob')
    befriend(env, a!, b!)
    const r = createReport(env.ctx, b!.uuid, { kind: 'player', reason: 'harassment', uuid: a!.uuid })
    const s = createSanction(env.ctx, modStaff, input(a!.uuid, 'warn', DAY))
    createAppeal(env.ctx, a!.uuid, s.id, 'Bitte die Verwarnung noch einmal ansehen.')
    const cape = uploadCape(env.ctx, a!.uuid, solidPng(64, 32), 'Blitz')
    createRoom(env.ctx, fresh(env, a!), SETTINGS)
    addPlayerNote(env.ctx, modStaff, a!.uuid, 'Beobachten,\nhäufig laut.')
    const notes = addPlayerNote(env.ctx, mod2Staff, a!.uuid, 'Zweite Notiz')
    expect(code(() => deletePlayerNote(env.ctx, modStaff, a!.uuid, notes[0]!.id))).toBe('admin_only')
    // Neuer Name → Verlauf.
    env.clock.advance(1000)
    await login(env, 'AlexNeu', a!.uuid)

    const f = playerFile(env.ctx, modStaff, a!.uuid)
    expect(f.player).toMatchObject({ name: 'AlexNeu', role: null, known: true, banned: false, friends: 1 })
    expect(f.names.map((n) => n.name)).toEqual(['AlexNeu', 'Alex'])
    expect(f.sanctions.map((x) => [x.kind, x.appeal?.status])).toEqual([['warn', 'open']])
    expect(f.warnings).toEqual({ total: 1, active: 1 })
    expect(f.reports.against.counts).toMatchObject({ total: 1, open: 1 })
    expect(f.reports.against.recent[0]).toMatchObject({ id: r.id, priority: 'high' })
    expect(f.reports.filed.counts.total).toBe(0)
    expect(f.reports.reporterScore).toEqual({ actioned: 0, dismissed: 0, low: false, score: null })
    expect(f.capes.map((c) => [c.id, c.status, c.source])).toEqual([[cape.id, 'pending', 'upload']])
    expect(f.worlds).toHaveLength(1)
    expect(f.notes.map((n) => [n.text, n.deletable])).toEqual([['Zweite Notiz', false], ['Beobachten,\nhäufig laut.', true]])
    expect(f.can).toMatchObject({ sanction: true, limits: { maxMinutes: 7 * DAY, permanent: false } })
    expect(playerFile(env.ctx, modStaff, mod.uuid).can).toMatchObject({ sanction: false, reason: 'self' })
    expect(playerFile(env.ctx, modStaff, ADMIN).can).toMatchObject({ sanction: false, reason: 'admin' })
    expect(code(() => playerFile(env.ctx, modStaff, 'c'.repeat(32)))).toBe('user_not_found')

    expect(search(env.ctx, 'ale').players.map((p) => [p.name, p.matched])).toEqual([['AlexNeu', null]])
    expect(search(env.ctx, 'alex').players).toHaveLength(1)
    expect(search(env.ctx, a!.uuid).players[0]!.uuid).toBe(a!.uuid)
    expect(search(env.ctx, r.id).reports.map((x) => x.id)).toEqual([r.id])
    expect(search(env.ctx, `#${s.id}`).sanctions.map((x) => x.id)).toEqual([s.id])
    expect(search(env.ctx, 'blitz').capes.map((c) => c.id)).toEqual([cape.id])

    expect(listPlayers(env.ctx, { status: 'sanctioned', sort: 'last_login', limit: 10 }).players.map((p) => p.name)).toEqual(['AlexNeu'])
    expect(listPlayers(env.ctx, { status: 'staff', sort: 'last_login', limit: 10 }).players.map((p) => p.name).sort()).toEqual(['Admin', 'Mod', 'Mod2'])
    expect(listPlayers(env.ctx, { q: 'al', status: 'all', sort: 'created', limit: 10 }).players.map((p) => p.name)).toEqual(['AlexNeu'])
    const p1 = listPlayers(env.ctx, { status: 'all', sort: 'created', limit: 2 })
    expect(p1.players).toHaveLength(2)
    expect(listPlayers(env.ctx, { status: 'all', sort: 'created', limit: 10, cursor: p1.nextCursor! }).players).toHaveLength(3)

    const d = dashboard(env.ctx)
    expect(d.reports).toMatchObject({ open: 1, highPriority: 1 })
    expect(d.appeals.open).toBe(1)
    expect(d.sanctions).toMatchObject({ warn: 1, chat_mute: 0 })
    expect(d.uploads.capesPending).toBe(1)
    expect(d.users).toMatchObject({ total: 5, new24h: 5 })
    expect(d.hosting.openRooms).toBe(1)
    expect(d.series.days).toHaveLength(30)
    expect(d.series.newUsers.at(-1)).toBe(5)
    expect(d.series.sanctions.at(-1)).toBe(1)
    expect(d.server.version).toMatch(/^\d+\.\d+\.\d+/)
    expect(d.recentAudit.length).toBeGreaterThan(0)
  })

  it('keeps the sanction history 2 years after the end; deleting the account keeps only active sanctions', async () => {
    const env = makeEnv()
    const { modStaff, players: [a, b] } = await team(env, 'Alex', 'Bob')
    const ended = createSanction(env.ctx, modStaff, input(a!.uuid, 'social_ban', HOUR))
    createSanction(env.ctx, SYSTEM, input(a!.uuid, 'chat_mute', null, { auto: 'reports', reasonCode: 'auto_reports' }))
    addPlayerNote(env.ctx, modStaff, a!.uuid, 'Notiz')
    env.clock.advance(365 * 86_400_000)
    sweepSanctions(env.ctx)
    expect(one(env.ctx.db, 'SELECT 1 AS x FROM sanctions WHERE id = ?', ended.id)).toBeDefined()
    env.clock.advance(366 * 86_400_000)
    sweepSanctions(env.ctx)
    expect(one(env.ctx.db, 'SELECT 1 AS x FROM sanctions WHERE id = ?', ended.id)).toBeUndefined()
    expect(one(env.ctx.db, 'SELECT 1 AS x FROM player_notes WHERE uuid = ?', a!.uuid)).toBeUndefined()
    expect(activeSanction(env.ctx, a!.uuid, 'chat_mute')).toBeDefined()

    const { deleteUser } = await import('../server/lib/users')
    createSanction(env.ctx, modStaff, input(b!.uuid, 'warn', HOUR))
    createSanction(env.ctx, modStaff, input(b!.uuid, 'upload_ban', DAY))
    addPlayerNote(env.ctx, modStaff, b!.uuid, 'bleibt wegen aktiver Sperre')
    deleteUser(env.ctx, b!.uuid)
    expect(all<{ kind: string }>(env.ctx.db, 'SELECT kind FROM sanctions WHERE uuid = ?', b!.uuid).map((x) => x.kind)).toEqual(['upload_ban'])
    expect(one(env.ctx.db, 'SELECT 1 AS x FROM player_notes WHERE uuid = ?', b!.uuid)).toBeDefined()
  })
})

describe('report actions with the new sanctions', () => {
  it('action=sanction applies any kind with template reason; moderators cannot ban via reports', async () => {
    const env = makeEnv()
    const { modStaff, players: [a, b] } = await team(env, 'Alex', 'Bob')
    const r = createReport(env.ctx, b!.uuid, { kind: 'player', reason: 'scam_phishing', uuid: a!.uuid })
    expect(code(() => adminReportAction(env.ctx, modStaff, r.id, { action: 'ban' }))).toBe('admin_only')
    const d = adminReportAction(env.ctx, modStaff, r.id, { action: 'sanction', kind: 'social_ban', duration: '3d', note: 'intern' })
    expect(d).toMatchObject({ status: 'resolved', outcome: 'actioned' })
    const s = activeSanction(env.ctx, a!.uuid, 'social_ban')!
    expect(s).toMatchObject({ reason_code: 'scam_phishing', report_id: r.id, note: 'intern', created_role: 'moderator' })
    expect(s.expires_at).toBe(env.clock.t + 3 * 86_400_000)
  })
})
