import { DatabaseSync } from 'node:sqlite'
import { describe, expect, it } from 'vitest'
import { createJob, eligibility, setApplicationStatus, submitApplication } from '../server/lib/applications'
import { catalog, setActiveCape } from '../server/lib/capes'
import {
  adminCreateCreator,
  adminDecideRequest,
  adminListCreators,
  adminRestoreCreator,
  adminRevokeCreator,
  adminUpdateCreator,
  blockCreator,
  createExtraCode,
  createRequest,
  creatorDashboard,
  creatorPreviews,
  decideQueueItem,
  defaultMainCode,
  endSupport,
  listCreatorPacks,
  myCreatorState,
  readSymbol,
  redeemCreatorCode,
  reviewQueue,
  sanitizeSymbol,
  setCreatorPackListed,
  setCreatorSettings,
  SWITCH_LOCK_MS,
  unblockCreator,
  updateCode,
  uploadFanCape,
  uploadSymbol,
  adminCreatePreview,
  answerHelperInvite,
  inviteHelper,
  leaveHelping,
  removeHelper,
  updateHelper,
} from '../server/lib/creators'
import { one, run } from '../server/lib/db'
import { lookupPlayers } from '../server/lib/lookup'
import { migrateCreators } from '../server/lib/migrations'
import { ownerStaff, setMemberRoles, teamOf } from '../server/lib/team'
import { getUser } from '../server/lib/users'
import type { PlayerEvent } from '../server/lib/watch'
import { befriend, code, listen, players } from './chathelpers'
import { ADMIN, chunk, login, makeEnv, solidPng, withChunks, type TestEnv } from './helpers'

const OWNER = ownerStaff(ADMIN)
const DAY = 86_400_000

async function player(env: TestEnv, name: string): Promise<string> {
  return (await login(env, name)).user.uuid
}

/** Creator anlegen; liefert UUID und Haupt-Code. */
async function creator(env: TestEnv, name: string, tier: 'starter' | 'partner' | 'top' = 'starter') {
  const uuid = await player(env, name)
  const c = adminCreateCreator(env.ctx, OWNER, { player: name, tier }).creator
  return { uuid, code: c.mainCode! }
}

/** Freigegebenes Symbol für einen Creator. */
function approvedSymbol(env: TestEnv, uuid: string, png = solidPng(32, 32)) {
  uploadSymbol(env.ctx, uuid, png, {})
  const item = reviewQueue(env.ctx).items.find((i) => i.kind === 'symbol' && i.creator.uuid === uuid)!
  decideQueueItem(env.ctx, OWNER, item.id, true)
  return item
}

/** Freigegebener Fan-Umhang (Partner+). */
function approvedFanCape(env: TestEnv, uuid: string) {
  const cape = uploadFanCape(env.ctx, uuid, solidPng(64, 32), { name: 'Drache' }).fanCape
  decideQueueItem(env.ctx, OWNER, `c${cape.capeId}`, true)
  return cape.capeId
}

const redeem = (env: TestEnv, fan: string, c: string) => redeemCreatorCode(env.ctx, fan, { code: c })

/** Viele Konten ohne Mojang-Ablauf (für Meilensteine). */
function bulkUsers(env: TestEnv, n: number, prefix: string): string[] {
  const out: string[] = []
  for (let i = 0; i < n; i++) {
    const uuid = `${prefix}${i.toString(16).padStart(32 - prefix.length, '0')}`
    run(env.ctx.db, 'INSERT INTO users (uuid, name, name_lower, created_at, last_login_at) VALUES (?, ?, ?, ?, ?)', uuid, `F${i}`, `f${i}`, env.clock.t, env.clock.t)
    out.push(uuid)
  }
  return out
}

function watch(env: TestEnv, viewer: string, uuids: string[]): PlayerEvent[] {
  const got: PlayerEvent[] = []
  env.ctx.watch.subscribe(viewer, uuids, (e) => got.push(e), () => {})
  return got
}

describe('creator codes and redeeming', () => {
  it('default main code, support, idempotent uses per fan and code', async () => {
    const env = makeEnv()
    expect(defaultMainCode('Pixel_Maus', 'a'.repeat(32))).toBe('TRS-PIXELMAUS')
    const pix = await creator(env, 'Pixelmaus')
    expect(pix.code).toBe('TRS-PIXELMAUS')
    const fan = await player(env, 'Steve')
    const r = redeem(env, fan, 'trs-pixelmaus')
    expect(r.supportChanged).toBe(true)
    expect(r.creator).toMatchObject({ uuid: pix.uuid, name: 'Pixelmaus', tier: 'starter', symbol: null, fanCape: null })
    expect(r.fanCapeGranted).toBe(false)
    expect(r.switchAvailableAt).toBe(new Date(env.clock.t + SWITCH_LOCK_MS).toISOString())
    // Gleicher Code nochmal: keine zweite Einlösung.
    const again = redeem(env, fan, 'TRS-PIXELMAUS')
    expect(again.supportChanged).toBe(false)
    const dash = creatorDashboard(env.ctx, pix.uuid)
    expect(dash.codes[0]).toMatchObject({ code: 'TRS-PIXELMAUS', kind: 'main', uses: 1 })
    expect(dash.stats).toMatchObject({ activeFans: 1, redemptions: 1, newPlayers: 1 })
    expect(dash.stats.daily).toHaveLength(30)
    expect(dash.stats.daily[29]).toMatchObject({ newFans: 1, redemptions: 1 })
    // Unbekannt, falsches Format, eigener Code.
    expect(code(() => redeem(env, fan, 'TRS-NOPE'))).toBe('creator_code_not_found')
    expect(code(() => redeem(env, fan, 'x'))).toBe('creator_code_not_found')
    expect(code(() => redeem(env, pix.uuid, pix.code))).toBe('creator_self')
  })

  it('switching: 30-day rule, fan cape without switch, same creator keeps the start', async () => {
    const env = makeEnv()
    const a = await creator(env, 'Alpha')
    const b = await creator(env, 'Bravo', 'partner')
    const capeId = approvedFanCape(env, b.uuid)
    const fan = await player(env, 'Steve')
    const t0 = env.clock.t
    redeem(env, fan, a.code)
    env.clock.advance(5 * DAY)
    const r = redeem(env, fan, b.code)
    expect(r.supportChanged).toBe(false)
    expect(r.switchAvailableAt).toBe(new Date(t0 + SWITCH_LOCK_MS).toISOString())
    expect(r.fanCapeGranted).toBe(true)
    expect(myCreatorState(env.ctx, fan).support.creator?.uuid).toBe(a.uuid)
    expect(myCreatorState(env.ctx, fan).fanCapes).toEqual([{ capeId, name: 'Drache', creator: { uuid: b.uuid, name: 'Bravo' } }])
    // Fan-Umhang verhält sich wie ein eigener (§5): Katalog, tragen, Lookup.
    expect(catalog(env.ctx, fan).find((c) => c.id === capeId)).toMatchObject({ owned: true, shareable: false })
    setActiveCape(env.ctx, fan, capeId)
    expect(lookupPlayers(env.ctx, fan, [fan]).players[0]!.cape?.id).toBe(capeId)

    // Beenden und zum selben Creator zurück: Start bleibt, auch innerhalb der 30 Tage.
    endSupport(env.ctx, fan)
    expect(myCreatorState(env.ctx, fan).support.creator).toBeNull()
    env.clock.advance(DAY)
    expect(code(() => redeem(env, fan, b.code))).toBe('ok')
    expect(myCreatorState(env.ctx, fan).support.creator).toBeNull()
    const back = redeem(env, fan, a.code)
    expect(back.supportChanged).toBe(true)
    expect(myCreatorState(env.ctx, fan).support.startedAt).toBe(new Date(t0).toISOString())

    // Nach 30 Tagen ab Start geht der Wechsel.
    env.clock.t = t0 + SWITCH_LOCK_MS + 1
    const sw = redeem(env, fan, b.code)
    expect(sw.supportChanged).toBe(true)
    expect(myCreatorState(env.ctx, fan).support).toMatchObject({ lastCreatorUuid: b.uuid, startedAt: new Date(env.clock.t).toISOString() })
    // Fan-Umhang behält man beim Wechsel/Beenden.
    redeem(env, fan, b.code)
    endSupport(env.ctx, fan)
    expect(myCreatorState(env.ctx, fan).fanCapes).toHaveLength(1)
  })

  it('extra codes: limits per tier, expiry, used up, main code fixed', async () => {
    const env = makeEnv()
    const s = await creator(env, 'Starter')
    expect(code(() => createExtraCode(env.ctx, s.uuid, { code: 'STARTER-YT', maxUses: 200 }))).toBe('creator_code_limit')
    expect(code(() => createExtraCode(env.ctx, s.uuid, { code: 'bad code' }))).toBe('invalid_code')
    expect(code(() => createExtraCode(env.ctx, s.uuid, { code: s.code }))).toBe('creator_code_taken')
    const codes = createExtraCode(env.ctx, s.uuid, { code: 'starter-yt', maxUses: 1, expiresAt: new Date(env.clock.t + 2 * DAY).toISOString() }).codes
    expect(codes[1]).toMatchObject({ code: 'STARTER-YT', kind: 'extra', maxUses: 1, active: true })
    expect(code(() => createExtraCode(env.ctx, s.uuid, { code: 'STARTER-TT' }))).toBe('creator_code_limit')
    expect(code(() => updateCode(env.ctx, s.uuid, s.code, { active: false }))).toBe('invalid_request')

    const [f1, f2, f3] = [await player(env, 'Fan1'), await player(env, 'Fan2'), await player(env, 'Fan3')]
    redeem(env, f1!, 'STARTER-YT')
    expect(code(() => redeem(env, f2!, 'STARTER-YT'))).toBe('creator_code_expired')
    // Wer schon eingelöst hat, darf idempotent nochmal.
    expect(code(() => redeem(env, f1!, 'STARTER-YT'))).toBe('ok')
    updateCode(env.ctx, s.uuid, 'STARTER-YT', { maxUses: 100 })
    expect(code(() => redeem(env, f2!, 'STARTER-YT'))).toBe('ok')
    env.clock.advance(3 * DAY)
    expect(code(() => redeem(env, f3!, 'STARTER-YT'))).toBe('creator_code_expired')
    // Aus → wie unbekannt; wieder an zählt aufs Limit.
    updateCode(env.ctx, s.uuid, 'STARTER-YT', { active: false })
    expect(code(() => redeem(env, f3!, 'STARTER-YT'))).toBe('creator_code_not_found')
    createExtraCode(env.ctx, s.uuid, { code: 'STARTER-TT' })
    expect(code(() => updateCode(env.ctx, s.uuid, 'STARTER-YT', { active: true }))).toBe('creator_code_limit')
    // Top: unbegrenzt.
    const top = await creator(env, 'Topper', 'top')
    for (let i = 0; i < 7; i++) createExtraCode(env.ctx, top.uuid, { code: `TOPPER-${i}0`, maxUses: 50_000 })
    expect(creatorDashboard(env.ctx, top.uuid).codes).toHaveLength(8)
  })

  it('blocked and inactive creators', async () => {
    const env = makeEnv()
    const c = await creator(env, 'Blocky')
    const other = await creator(env, 'Other')
    const fan = await player(env, 'Steve')
    expect(blockCreator(env.ctx, fan, { code: c.code.toLowerCase() }).blocked).toEqual([{ uuid: c.uuid, name: 'Blocky' }])
    expect(code(() => redeem(env, fan, c.code))).toBe('creator_blocked')
    expect(code(() => blockCreator(env.ctx, fan, { code: 'TRS-UNKNOWN' }))).toBe('creator_code_not_found')
    unblockCreator(env.ctx, fan, c.uuid)
    redeem(env, fan, c.code)
    expect(code(() => blockCreator(env.ctx, fan, { code: c.code }))).toBe('creator_supported')
    expect(code(() => blockCreator(env.ctx, fan, { code: other.code }))).toBe('ok')

    adminRevokeCreator(env.ctx, OWNER, c.uuid, { reason: 'Codes gespammt', ban: false, removeFanCapes: false })
    const fan2 = await player(env, 'Alex')
    expect(code(() => redeem(env, fan2, c.code))).toBe('creator_code_not_found')
    expect(code(() => creatorDashboard(env.ctx, c.uuid))).toBe('creator_inactive')
    expect(code(() => creatorDashboard(env.ctx, fan2))).toBe('creator_inactive')
    // Support-Zeile bleibt, aber versteckt; der entzogene Creator hält niemanden in der 30-Tage-Sperre.
    expect(myCreatorState(env.ctx, fan).support.creator).toBeNull()
    expect(myCreatorState(env.ctx, fan).creator).toBeNull()
    expect(myCreatorState(env.ctx, c.uuid).creator).toMatchObject({ state: 'revoked', reason: 'Codes gespammt' })
    unblockCreator(env.ctx, fan, other.uuid)
    expect(redeem(env, fan, other.code).supportChanged).toBe(true)
  })
})

describe('lookup', () => {
  it('creator badge, fan symbol, blocks and showFanSymbols per requester', async () => {
    const env = makeEnv()
    const c = await creator(env, 'Pixelmaus', 'partner')
    const fan = await player(env, 'Steve')
    const viewer = await player(env, 'Alex')
    redeem(env, fan, c.code)
    // Ohne freigegebenes Symbol kein `fan`; nur-Creator-Einträge fehlen nicht mehr.
    let res = lookupPlayers(env.ctx, viewer, [fan, c.uuid]).players
    expect(res.map((p) => p.uuid)).toEqual([c.uuid])
    expect(res[0]!.creator).toEqual({ tier: 'partner' })
    const item = approvedSymbol(env, c.uuid, solidPng(16, 32))
    res = lookupPlayers(env.ctx, viewer, [fan]).players
    expect(res[0]!.fan).toEqual({
      creator: c.uuid, name: 'Pixelmaus', milestone: null,
      symbol: { url: item.symbol!.url, size: 16, frames: 2, frameMs: 150, sha256: item.symbol!.sha256 },
    })
    expect(res[0]!.badge).toBe(false)
    // Andere ausblenden: fremde weg, eigener bleibt.
    expect(setCreatorSettings(env.ctx, viewer, { showFanSymbols: false })).toEqual({ showFanSymbols: false })
    expect(lookupPlayers(env.ctx, viewer, [fan]).players).toEqual([])
    setCreatorSettings(env.ctx, fan, { showFanSymbols: false })
    expect(lookupPlayers(env.ctx, fan, [fan]).players[0]!.fan?.creator).toBe(c.uuid)
    setCreatorSettings(env.ctx, viewer, { showFanSymbols: true })
    // Blockiert: weder Abzeichen noch Symbol dieses Creators.
    blockCreator(env.ctx, viewer, { code: c.code })
    expect(lookupPlayers(env.ctx, viewer, [fan, c.uuid]).players).toEqual([])
    unblockCreator(env.ctx, viewer, c.uuid)
    // Entzogen: alles weg.
    adminRevokeCreator(env.ctx, OWNER, c.uuid, { reason: 'x', ban: true, removeFanCapes: false })
    expect(lookupPlayers(env.ctx, viewer, [fan, c.uuid]).players).toEqual([])
  })

  it('player events: fan on support changes, fan_creator on symbol approval', async () => {
    const env = makeEnv()
    const c = await creator(env, 'Pixelmaus', 'partner')
    const fan = await player(env, 'Steve')
    const viewer = await player(env, 'Alex')
    const got = watch(env, viewer, [fan])
    redeem(env, fan, c.code)
    expect(got).toEqual([{ type: 'fan', uuid: fan }])
    approvedSymbol(env, c.uuid)
    expect(got.at(-1)).toEqual({ type: 'fan_creator', creator: c.uuid })
    const before = got.length
    endSupport(env.ctx, fan)
    expect(got.slice(before)).toEqual([{ type: 'fan', uuid: fan }])
  })
})

describe('symbols', () => {
  it('validates size, frames by tier, APNG, transparency and size limit', async () => {
    const env = makeEnv()
    expect(sanitizeSymbol(solidPng(16, 16), 1)).toMatchObject({ size: 16, frames: 1 })
    expect(sanitizeSymbol(solidPng(64, 512), 8)).toMatchObject({ size: 64, frames: 8 })
    const bad = (png: Buffer, max = 8) => {
      try {
        sanitizeSymbol(png, max)
        return 'ok'
      } catch (e) {
        const err = e as { code: string, details?: { reason?: string } }
        return `${err.code}${err.details?.reason ? `:${err.details.reason}` : ''}`
      }
    }
    expect(bad(solidPng(32, 64), 1)).toBe('creator_symbol_frames')
    expect(bad(solidPng(15, 15))).toBe('invalid_symbol:invalid_dimensions')
    expect(bad(solidPng(65, 65))).toBe('invalid_symbol:invalid_dimensions')
    expect(bad(solidPng(32, 40))).toBe('invalid_symbol:invalid_dimensions')
    expect(bad(solidPng(16, 16 * 9))).toBe('invalid_symbol:too_many_frames')
    expect(bad(solidPng(16, 16, [0, 0, 0, 0]))).toBe('invalid_symbol:empty')
    expect(bad(withChunks(solidPng(16, 16), [chunk('acTL', Buffer.alloc(8))], true))).toBe('invalid_symbol:animated_png')
    expect(bad(Buffer.from('not a png'))).toBe('invalid_symbol:invalid_png')
    expect(bad(Buffer.alloc(300 * 1024))).toBe('payload_too_large')
    // Neu kodiert: keine Zusatz-Chunks.
    const clean = sanitizeSymbol(withChunks(solidPng(16, 16), [chunk('tEXt', Buffer.from('Comment\0hi'))]), 1)
    expect(clean.png.includes(Buffer.from('tEXt'))).toBe(false)

    const s = await creator(env, 'Starter')
    expect(code(() => uploadSymbol(env.ctx, s.uuid, solidPng(16, 32), {}))).toBe('creator_symbol_frames')
    const p = await creator(env, 'Partner', 'partner')
    expect(uploadSymbol(env.ctx, p.uuid, solidPng(16, 32), { frameMs: '80' }).symbol).toMatchObject({ size: 16, frames: 2, frameMs: 80 })
    expect(code(() => uploadSymbol(env.ctx, p.uuid, solidPng(16, 16), { frameMs: '20' }))).toBe('invalid_request')
  })

  it('pending replaces pending; review approve/reject with events; public file only when approved', async () => {
    const env = makeEnv()
    const c = await creator(env, 'Pixelmaus', 'partner')
    const ev = listen(env, c.uuid)
    const first = uploadSymbol(env.ctx, c.uuid, solidPng(32, 32, [1, 2, 3, 255]), {}).symbol
    const second = uploadSymbol(env.ctx, c.uuid, solidPng(32, 32, [4, 5, 6, 255]), {}).symbol
    expect(reviewQueue(env.ctx).items).toHaveLength(1)
    expect(code(() => readSymbol(env.ctx, first.sha256, null))).toBe('symbol_not_found')
    expect(code(() => readSymbol(env.ctx, second.sha256, null))).toBe('symbol_not_found')
    expect(readSymbol(env.ctx, second.sha256, { uuid: c.uuid, admin: false }).public).toBe(false)
    const id = reviewQueue(env.ctx).items[0]!.id
    expect(code(() => decideQueueItem(env.ctx, OWNER, id, false, {}))).toBe('invalid_request')
    decideQueueItem(env.ctx, OWNER, id, false, { reason: 'Enthält ein fremdes Logo' })
    expect(ev.of('creator_updated')).toEqual([{ type: 'creator_updated', kind: 'symbol_rejected', reason: 'Enthält ein fremdes Logo' }])
    expect(creatorDashboard(env.ctx, c.uuid).symbol).toMatchObject({ current: null, pending: null, rejected: { reason: 'Enthält ein fremdes Logo' } })
    expect(code(() => decideQueueItem(env.ctx, OWNER, id, true))).toBe('queue_item_not_found')

    const third = uploadSymbol(env.ctx, c.uuid, solidPng(32, 32, [7, 8, 9, 255]), {}).symbol
    decideQueueItem(env.ctx, OWNER, reviewQueue(env.ctx).items[0]!.id, true)
    expect(ev.of('creator_updated').at(-1)).toEqual({ type: 'creator_updated', kind: 'symbol_approved' })
    expect(readSymbol(env.ctx, third.sha256, null).public).toBe(true)
    // Das neue wartet, Fans sehen weiter das alte.
    uploadSymbol(env.ctx, c.uuid, solidPng(32, 32, [9, 9, 9, 255]), {})
    const dash = creatorDashboard(env.ctx, c.uuid).symbol
    expect(dash.current?.sha256).toBe(third.sha256)
    expect(dash.pending).not.toBeNull()
    expect(dash.rejected).toBeNull()
  })

  it('fan capes: tier rules, review, rejected cape not granted', async () => {
    const env = makeEnv()
    const s = await creator(env, 'Starter')
    expect(code(() => uploadFanCape(env.ctx, s.uuid, solidPng(64, 32), {}))).toBe('creator_tier')
    const p = await creator(env, 'Partner', 'partner')
    expect(code(() => uploadFanCape(env.ctx, p.uuid, solidPng(1024, 512), {}))).toBe('creator_tier')
    expect(code(() => uploadFanCape(env.ctx, p.uuid, solidPng(64, 64), { frameTimeMs: '100' }))).toBe('creator_tier')
    const t = await creator(env, 'Topper', 'top')
    expect(uploadFanCape(env.ctx, t.uuid, solidPng(64, 64), { frameTimeMs: '100' }).fanCape.capeId).toMatch(/^u/)
    const ev = listen(env, p.uuid)
    const cape = uploadFanCape(env.ctx, p.uuid, solidPng(512, 256), {}).fanCape
    // Wartende Fan-Umhänge gibt es nicht beim Einlösen.
    const fan = await player(env, 'Steve')
    expect(redeem(env, fan, p.code).fanCapeGranted).toBe(false)
    decideQueueItem(env.ctx, OWNER, `c${cape.capeId}`, false, { reason: 'Unscharf' })
    expect(ev.of('creator_updated')).toEqual([{ type: 'creator_updated', kind: 'cape_rejected', reason: 'Unscharf' }])
    expect(creatorDashboard(env.ctx, p.uuid).fanCape).toMatchObject({ current: null, rejected: { reason: 'Unscharf' } })
    const ok = approvedFanCape(env, p.uuid)
    expect(creatorDashboard(env.ctx, p.uuid).fanCape.current?.capeId).toBe(ok)
    expect(redeem(env, fan, p.code).fanCapeGranted).toBe(true)
    expect(creatorDashboard(env.ctx, p.uuid).stats.fanCapeHolders).toBe(1)
  })
})

describe('milestones', () => {
  it('reach bronze at 100 active fans, events to creator and fans, never decrease', async () => {
    const env = makeEnv()
    const c = await creator(env, 'Pixelmaus', 'partner')
    approvedSymbol(env, c.uuid)
    const fans = bulkUsers(env, 100, 'f')
    const ce = listen(env, c.uuid)
    const fe = listen(env, fans[0]!)
    for (const f of fans.slice(0, 99)) redeem(env, f, c.code)
    expect(creatorDashboard(env.ctx, c.uuid)).toMatchObject({ milestone: null, nextMilestone: { name: 'bronze', at: 100 } })
    redeem(env, fans[99]!, c.code)
    expect(creatorDashboard(env.ctx, c.uuid)).toMatchObject({ milestone: 'bronze', nextMilestone: { name: 'silver', at: 500 } })
    expect(ce.of('creator_updated')).toEqual([{ type: 'creator_updated', kind: 'milestone' }])
    expect(fe.of('creator_milestone')).toEqual([{ type: 'creator_milestone', creator: { uuid: c.uuid, name: 'Pixelmaus' }, milestone: 'bronze' }])
    expect(lookupPlayers(env.ctx, fans[1]!, [fans[0]!]).players[0]!.fan?.milestone).toBe('bronze')
    for (const f of fans.slice(0, 10)) endSupport(env.ctx, f)
    redeem(env, fans[0]!, c.code)
    expect(creatorDashboard(env.ctx, c.uuid).milestone).toBe('bronze')
    expect(ce.of('creator_updated').filter((e) => e.kind === 'milestone')).toHaveLength(1)
  })
})

describe('team actions', () => {
  it('revoke/ban with removeFanCapes, restore, tier change, main code', async () => {
    const env = makeEnv()
    const c = await creator(env, 'Pixelmaus', 'partner')
    const capeId = approvedFanCape(env, c.uuid)
    const [f1, f2] = [await player(env, 'Steve'), await player(env, 'Alex')]
    redeem(env, f1, c.code)
    redeem(env, f2, c.code)
    setActiveCape(env.ctx, f1, capeId)
    const ce = listen(env, c.uuid)
    const capeEvents = watch(env, f2, [f1])

    // Ohne „Fan-Umhänge entfernen“ behalten Fans sie.
    adminRevokeCreator(env.ctx, OWNER, c.uuid, { reason: 'Pause', ban: false, removeFanCapes: false })
    expect(myCreatorState(env.ctx, f1).fanCapes).toHaveLength(1)
    adminRestoreCreator(env.ctx, OWNER, c.uuid)
    expect(code(() => redeem(env, f2, c.code))).toBe('ok')

    const banned = adminRevokeCreator(env.ctx, OWNER, c.uuid, { reason: 'Betrug', ban: true, removeFanCapes: true }).creator
    expect(banned).toMatchObject({ state: 'banned', reason: 'Betrug' })
    expect(myCreatorState(env.ctx, f1).fanCapes).toEqual([])
    expect(one(env.ctx.db, 'SELECT active_cape_id AS a FROM users WHERE uuid = ?', f1)).toEqual({ a: null })
    expect(capeEvents.some((e) => e.type === 'cape' && e.uuid === f1)).toBe(true)
    expect(ce.of('creator_updated').map((e) => e.kind)).toEqual(['revoked', 'restored', 'revoked'])
    expect(adminListCreators(env.ctx, { state: 'banned' }).creators.map((x) => x.uuid)).toEqual([c.uuid])
    expect(one(env.ctx.db, "SELECT COUNT(*) AS n FROM admin_log WHERE action IN ('creator.revoke', 'creator.ban', 'creator.restore')")).toEqual({ n: 3 })

    adminRestoreCreator(env.ctx, OWNER, c.uuid)
    const upd = adminUpdateCreator(env.ctx, OWNER, c.uuid, {
      tier: 'top', mainCode: 'pixel', channels: [{ platform: 'youtube', url: 'https://youtube.com/@pixel', followers: 12000 }],
    }).creator
    expect(upd).toMatchObject({ tier: 'top', mainCode: 'PIXEL', channels: [{ platform: 'youtube' }] })
    expect(ce.of('creator_updated').at(-1)).toEqual({ type: 'creator_updated', kind: 'tier_changed', tier: 'top' })
    expect(code(() => redeem(env, f1, 'TRS-PIXELMAUS'))).toBe('creator_code_not_found')
    expect(code(() => adminUpdateCreator(env.ctx, OWNER, c.uuid, { channels: [{ platform: 'youtube', url: 'javascript:alert(1)', followers: 1 }] }))).toBe('invalid_request')
    const other = await player(env, 'Other')
    adminCreateCreator(env.ctx, OWNER, { player: other, tier: 'starter' })
    expect(code(() => adminUpdateCreator(env.ctx, OWNER, other, { mainCode: 'PIXEL' }))).toBe('creator_code_taken')
    expect(code(() => adminCreateCreator(env.ctx, OWNER, { player: 'Other', tier: 'starter' }))).toBe('already_creator')
    expect(code(() => adminCreateCreator(env.ctx, OWNER, { player: 'Nobody', tier: 'starter' }))).toBe('player_not_found')
  })

  it('requests: one open per kind, accepting an upgrade raises the tier', async () => {
    const env = makeEnv()
    const c = await creator(env, 'Pixelmaus')
    const ce = listen(env, c.uuid)
    const r = createRequest(env.ctx, c.uuid, { kind: 'upgrade', channelUrl: 'https://twitch.tv/pixel', followers: 1500 }).requests
    expect(r[0]).toMatchObject({ kind: 'upgrade', status: 'open' })
    expect(code(() => createRequest(env.ctx, c.uuid, { kind: 'upgrade', channelUrl: 'https://twitch.tv/pixel', followers: 1500 }))).toBe('creator_request_open')
    expect(code(() => createRequest(env.ctx, c.uuid, { kind: 'team_cape', text: '' }))).toBe('invalid_request')
    createRequest(env.ctx, c.uuid, { kind: 'team_cape', text: 'Lila Drache, Logo oben' })
    expect(code(() => creatorPreviews(env.ctx, c.uuid))).toBe('creator_tier')
    adminDecideRequest(env.ctx, OWNER, r[0]!.id, { status: 'accepted' })
    expect(creatorDashboard(env.ctx, c.uuid).tier).toBe('partner')
    expect(ce.of('creator_updated').map((e) => e.kind)).toEqual(['request_answered', 'tier_changed'])
    expect(code(() => adminDecideRequest(env.ctx, OWNER, r[0]!.id, { status: 'declined' }))).toBe('request_closed')
    adminCreatePreview(env.ctx, OWNER, { product: 'launcher', version: '0.22.0-pre.1', url: 'https://example.test/a.exe', sha256: 'A'.repeat(64) })
    expect(creatorPreviews(env.ctx, c.uuid).previews[0]).toMatchObject({ product: 'launcher', version: '0.22.0-pre.1', sha256: 'a'.repeat(64) })
    expect(code(() => adminCreatePreview(env.ctx, OWNER, { product: 'launcher', version: '1', url: 'http://x.test', sha256: 'a'.repeat(64) }))).toBe('invalid_request')
  })

  it('creator packs: list own packs, never expire while listed, blocks and inactive creators hidden', async () => {
    const env = makeEnv()
    const c = await creator(env, 'Pixelmaus')
    const other = await player(env, 'Other')
    const viewer = await player(env, 'Alex')
    const pack = (id: string, packCode: string, owner: string, installs: number) => run(
      env.ctx.db,
      `INSERT INTO shared_packs (id, code, owner_uuid, name, summary, pack_version, mc_version, loader, loader_version, index_files,
         own_jars, other_files, revision, bytes, sha256, duration, installs, created_at, updated_at, expires_at)
       VALUES (?, ?, ?, ?, NULL, '1.0', '1.21.11', 'fabric', NULL, 1, 0, 0, 1, 10, ?, '7d', ?, ?, ?, ?)`,
      id, packCode, owner, `Pack ${packCode}`, 'a'.repeat(64), installs, env.clock.t, env.clock.t, env.clock.t + 7 * DAY,
    )
    pack('p'.repeat(22), 'ABCD2345', c.uuid, 40)
    pack('q'.repeat(22), 'WXYZ6789', other, 99)
    expect(code(() => setCreatorPackListed(env.ctx, c.uuid, 'TRS-WXYZ-6789', { listed: true }))).toBe('not_your_pack')
    expect(code(() => setCreatorPackListed(env.ctx, c.uuid, 'TRS-0000-0000', { listed: true }))).toBe('pack_not_found')
    const packs = setCreatorPackListed(env.ctx, c.uuid, 'trs-abcd-2345', { listed: true }).packs
    expect(packs).toEqual([{ code: 'TRS-ABCD-2345', name: 'Pack ABCD2345', installs: 40, listed: true }])
    expect(one(env.ctx.db, 'SELECT expires_at AS e FROM shared_packs WHERE code = ?', 'ABCD2345')).toEqual({ e: null })
    const list = listCreatorPacks(env.ctx, null, {})
    expect(list).toEqual({ total: 1, packs: [{ code: 'TRS-ABCD-2345', name: 'Pack ABCD2345', mcVersion: '1.21.11', loader: 'fabric', installs: 40, creator: { uuid: c.uuid, name: 'Pixelmaus', tier: 'starter' } }] })
    expect(listCreatorPacks(env.ctx, null, { q: 'pixel' }).total).toBe(1)
    expect(listCreatorPacks(env.ctx, null, { q: 'nothing' }).total).toBe(0)
    expect(code(() => listCreatorPacks(env.ctx, null, { limit: '500' }))).toBe('invalid_request')
    blockCreator(env.ctx, viewer, { code: c.code })
    expect(listCreatorPacks(env.ctx, viewer, {}).total).toBe(0)
    expect(creatorDashboard(env.ctx, c.uuid).stats.packInstalls).toBe(40)
    adminRevokeCreator(env.ctx, OWNER, c.uuid, { reason: 'x', ban: false, removeFanCapes: false })
    expect(listCreatorPacks(env.ctx, null, {}).total).toBe(0)
    expect(one<{ e: number | null, l: number }>(env.ctx.db, 'SELECT expires_at AS e, creator_listed AS l FROM shared_packs WHERE code = ?', 'ABCD2345')).toEqual({ e: env.clock.t + 7 * DAY, l: 0 })
  })

  it('permissions: content manages, senior moderators review, moderators nothing', async () => {
    const env = makeEnv()
    await login(env, 'Owner', ADMIN)
    const [content, senior, mod] = [await player(env, 'Content'), await player(env, 'Senior'), await player(env, 'Mod')]
    setMemberRoles(env.ctx, OWNER, content, ['content'])
    setMemberRoles(env.ctx, OWNER, senior, ['senior_moderator'])
    setMemberRoles(env.ctx, OWNER, mod, ['moderator'])
    const st = (u: string) => teamOf(env.ctx, u)!
    expect([...st(content).perms].filter((p) => p.startsWith('creators.')).sort()).toEqual(['creators.manage', 'creators.review'])
    expect([...st(senior).perms].filter((p) => p.startsWith('creators.'))).toEqual(['creators.review'])
    expect([...st(mod).perms].filter((p) => p.startsWith('creators.'))).toEqual([])
    await player(env, 'Pixelmaus')
    expect(code(() => adminCreateCreator(env.ctx, st(senior), { player: 'Pixelmaus', tier: 'partner' }))).toBe('missing_permission')
    const c = adminCreateCreator(env.ctx, st(content), { player: 'Pixelmaus', tier: 'partner' }).creator
    uploadSymbol(env.ctx, c.uuid, solidPng(16, 16), {})
    const id = reviewQueue(env.ctx).items[0]!.id
    expect(code(() => decideQueueItem(env.ctx, st(mod), id, true))).toBe('missing_permission')
    expect(code(() => decideQueueItem(env.ctx, st(senior), id, true))).toBe('ok')
    expect(code(() => adminRevokeCreator(env.ctx, st(senior), c.uuid, { reason: 'x', ban: false, removeFanCapes: false }))).toBe('missing_permission')
    expect(one(env.ctx.db, "SELECT COUNT(*) AS n FROM admin_log WHERE action = 'creator.symbol.approve' AND actor = ?", senior)).toEqual({ n: 1 })
  })
})

describe('applications', () => {
  it('accepting an application to a creator job makes the applicant a creator', async () => {
    const env = makeEnv()
    await login(env, 'Owner', ADMIN)
    const job = createJob(env.ctx, OWNER, {
      status: 'open', purpose: 'creator', roleId: null,
      texts: { en: { title: 'Creator', summary: 'Make videos', description: 'Show TRS.', tasks: [], requirements: [] } },
      form: [],
    })
    expect(job.purpose).toBe('creator')
    expect(code(() => createJob(env.ctx, OWNER, { status: 'open', purpose: 'creator', roleId: 'moderator', texts: { en: { title: 'X', summary: 'y', description: 'z', tasks: [], requirements: [] } } }))).toBe('invalid_role')
    // Haupt-Code belegt → Zahl anhängen.
    const taken = await player(env, 'Pixel')
    adminCreateCreator(env.ctx, OWNER, { player: taken, tier: 'starter', mainCode: 'TRS-PIXELMAUS' })
    const uuid = await player(env, 'Pixel_Maus')
    const ev = listen(env, uuid)
    const app = submitApplication(env.ctx, { uuid, name: 'Pixel_Maus' }, job.id, { discord: 'pixel.maus', ageGroup: '18+', lang: 'de', answers: {} })
    expect(myCreatorState(env.ctx, uuid).application).toMatchObject({ id: app.id, status: 'new' })
    expect(code(() => setApplicationStatus(env.ctx, OWNER, app.id, { status: 'accepted', grantRole: true }))).toBe('invalid_request')
    setApplicationStatus(env.ctx, OWNER, app.id, { status: 'accepted' })
    const me = myCreatorState(env.ctx, uuid)
    expect(me.creator).toMatchObject({ tier: 'starter', state: 'active', codes: [{ code: 'TRS-PIXELMAUS2', kind: 'main' }] })
    expect(me.application?.status).toBe('accepted')
    expect(ev.of('creator_updated')).toEqual([{ type: 'creator_updated', kind: 'became_creator', tier: 'starter' }])
    expect(eligibility(env.ctx, uuid, job.id).reason).toBe('already_creator')
    adminRevokeCreator(env.ctx, OWNER, uuid, { reason: 'Spam', ban: true, removeFanCapes: false })
    expect(eligibility(env.ctx, uuid, job.id).reason).toBe('creator_banned')
  })
})

describe('migration 25', () => {
  it('is idempotent and widens user_capes.source', () => {
    const env = makeEnv()
    migrateCreators(env.ctx.db)
    migrateCreators(env.ctx.db)
    const sql = (one<{ sql: string }>(env.ctx.db, "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'user_capes'")!).sql
    expect(sql).toContain("'creator'")
    const db = new DatabaseSync(':memory:')
    db.exec('CREATE TABLE users (uuid TEXT PRIMARY KEY)')
    db.exec('CREATE TABLE capes (id TEXT PRIMARY KEY)')
    migrateCreators(db)
    migrateCreators(db)
    for (const t of ['creators', 'creator_codes', 'creator_supports', 'creator_symbols', 'creator_requests', 'creator_previews', 'creator_daily', 'creator_blocks']) {
      expect(db.prepare("SELECT 1 AS x FROM sqlite_master WHERE type = 'table' AND name = ?").get(t)).toBeTruthy()
    }
  })
})

describe('helper accounts (§35.9)', () => {
  it('friends only, limits per tier, accept, rights per route, audit, revoke suspends, leave/remove events', async () => {
    const env = makeEnv()
    const c = await creator(env, 'Pixelmaus')
    const creatorRow = getUser(env.ctx, c.uuid)
    const [editor, mod2, stranger] = await players(env, 'Editor', 'Mod2', 'Stranger')
    const ed = editor!.uuid
    befriend(env, creatorRow!, editor!)
    befriend(env, creatorRow!, mod2!)
    const ce = listen(env, c.uuid)
    const he = listen(env, ed)
    expect(code(() => inviteHelper(env.ctx, c.uuid, { uuid: stranger!.uuid, rights: ['codes'] }))).toBe('not_friends')
    expect(code(() => inviteHelper(env.ctx, c.uuid, { uuid: ed, rights: [] }))).toBe('invalid_request')
    expect(inviteHelper(env.ctx, c.uuid, { uuid: ed, rights: ['codes'] }).helpers).toMatchObject([{ uuid: ed, name: 'Editor', rights: ['codes'], status: 'pending' }])
    expect(he.of('creator_helper_invite')).toMatchObject([{ creator: { uuid: c.uuid, name: 'Pixelmaus' }, rights: ['codes'] }])
    expect(code(() => inviteHelper(env.ctx, c.uuid, { uuid: ed, rights: ['codes'] }))).toBe('creator_helper_exists')
    expect(code(() => inviteHelper(env.ctx, c.uuid, { uuid: mod2!.uuid, rights: ['stats'] }))).toBe('creator_helper_limit')
    // Noch nicht angenommen → kein Zugriff.
    expect(code(() => creatorDashboard(env.ctx, ed, c.uuid))).toBe('creator_helper_right')
    expect(myCreatorState(env.ctx, ed).helperInvites).toMatchObject([{ creator: { uuid: c.uuid }, rights: ['codes'] }])
    expect(answerHelperInvite(env.ctx, ed, c.uuid, { accept: true }).helping).toEqual([{ creator: { uuid: c.uuid, name: 'Pixelmaus', tier: 'starter' }, rights: ['codes'] }])
    expect(ce.of('creator_updated')).toEqual([{ type: 'creator_updated', kind: 'helper_joined', helper: { uuid: ed, name: 'Editor' } }])

    // Sicht als Helfer: keine Statistik ohne `stats`, keine Helferliste, viewer gesetzt.
    const view = creatorDashboard(env.ctx, ed, c.uuid)
    expect(view.stats).toBeNull()
    expect(view.helpers).toBeUndefined()
    expect(view.viewer).toEqual({ helper: true, rights: ['codes'] })
    expect(creatorDashboard(env.ctx, c.uuid).helpers).toHaveLength(1)
    // Rechte je Route.
    createExtraCode(env.ctx, ed, { code: 'PIXEL-ED' }, c.uuid)
    expect(one(env.ctx.db, "SELECT actor, target FROM admin_log WHERE action = 'creator.helper.code.create'")).toEqual({ actor: ed, target: c.uuid })
    expect(code(() => updateCode(env.ctx, ed, c.code, { active: false }, c.uuid))).toBe('invalid_request')
    expect(code(() => uploadSymbol(env.ctx, ed, solidPng(16, 16), {}, c.uuid))).toBe('creator_helper_right')
    expect(code(() => createRequest(env.ctx, ed, { kind: 'team_cape', text: 'x' }, c.uuid))).toBe('creator_helper_right')
    expect(code(() => creatorPreviews(env.ctx, ed, c.uuid))).toBe('creator_helper_right')
    expect(code(() => inviteHelper(env.ctx, ed, { uuid: mod2!.uuid, rights: ['codes'] }))).toBe('creator_inactive')
    expect(code(() => creatorDashboard(env.ctx, stranger!.uuid, c.uuid))).toBe('creator_helper_right')
    updateHelper(env.ctx, c.uuid, ed, { rights: ['uploads', 'stats', 'packs'] })
    expect(code(() => uploadSymbol(env.ctx, ed, solidPng(16, 16), { as: c.uuid }, c.uuid))).toBe('ok')
    expect(creatorDashboard(env.ctx, ed, c.uuid).stats).not.toBeNull()
    expect(code(() => createExtraCode(env.ctx, ed, { code: 'PIXEL-TWO' }, c.uuid))).toBe('creator_helper_right')
    // Eigene Packs des Helfers sind keine Creator-Packs.
    run(
      env.ctx.db,
      `INSERT INTO shared_packs (id, code, owner_uuid, name, summary, pack_version, mc_version, loader, loader_version, index_files,
         own_jars, other_files, revision, bytes, sha256, duration, installs, created_at, updated_at, expires_at)
       VALUES (?, 'HJKM2345', ?, 'Helper pack', NULL, '1', '1.21.11', 'fabric', NULL, 1, 0, 0, 1, 1, ?, '7d', 0, ?, ?, ?)`,
      'h'.repeat(22), ed, 'b'.repeat(64), env.clock.t, env.clock.t, env.clock.t + DAY,
    )
    expect(code(() => setCreatorPackListed(env.ctx, ed, 'TRS-HJKM-2345', { listed: true }, c.uuid))).toBe('not_your_pack')
    // Entzug setzt Helfer aus.
    adminRevokeCreator(env.ctx, OWNER, c.uuid, { reason: 'Pause', ban: false, removeFanCapes: false })
    expect(code(() => creatorDashboard(env.ctx, ed, c.uuid))).toBe('creator_inactive')
    expect(myCreatorState(env.ctx, ed).helping).toEqual([])
    adminRestoreCreator(env.ctx, OWNER, c.uuid)
    expect(adminListCreators(env.ctx, {}).creators[0]!.helpers).toMatchObject([{ uuid: ed, status: 'active' }])
    // Aufhören / entfernen.
    leaveHelping(env.ctx, ed, c.uuid)
    expect(ce.of('creator_updated').at(-1)).toMatchObject({ kind: 'helper_left', helper: { uuid: ed } })
    inviteHelper(env.ctx, c.uuid, { uuid: mod2!.uuid, rights: ['stats'] })
    const me2 = listen(env, mod2!.uuid)
    removeHelper(env.ctx, c.uuid, mod2!.uuid)
    expect(me2.of('creator_helper_removed')).toEqual([{ type: 'creator_helper_removed', creator: { uuid: c.uuid, name: 'Pixelmaus' } }])
    expect(code(() => answerHelperInvite(env.ctx, mod2!.uuid, c.uuid, { accept: true }))).toBe('helper_invite_not_found')
  })
})
