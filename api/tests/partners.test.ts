import { createHmac } from 'node:crypto'
import { createServer, Socket, type AddressInfo, type Server } from 'node:net'
import { DatabaseSync } from 'node:sqlite'
import { afterEach, describe, expect, it } from 'vitest'
import { catalog, getCape, setActiveCape, uploadCape } from '../server/lib/capes'
import { shareRole } from '../server/lib/capeshares'
import { createCodes } from '../server/lib/codes'
import { adminCreateCreator, adminUpdateCreator, createExtraCode, normalizeCreatorCode } from '../server/lib/creators'
import { all, one, run } from '../server/lib/db'
import type { ApiError } from '../server/lib/errors'
import { migratePartners, migratePartners2, migratePartners3 } from '../server/lib/migrations'
import {
  compareVersions,
  listPartnerServers,
  parseMcVersion,
  pingPartnerServers,
  recordServerJoin,
  serverJoinStats,
} from '../server/lib/partner-servers'
import { adminPartnerStats, adminPartnerStatsDetail, clearPartnerStatsCache, snapshotPartnerActivity } from '../server/lib/partner-stats'
import {
  checkWebhookUrl,
  deliverOne,
  processDeliveries,
  queueWebhook,
  resolvePublic,
  retryDelivery,
  RETRY_DELAYS_MS,
  saveWebhook,
  sendTestWebhook,
  signWebhook,
  type WebhookSender,
} from '../server/lib/partner-webhooks'
import {
  addCampaign,
  adminAddFixedCode,
  adminAddItem,
  adminCatalog,
  adminCreatePartner,
  adminDecideRequest,
  adminDeleteFixedCode,
  adminGrantQuota,
  adminListPartners,
  adminListRequests,
  adminUpdateFixedCode,
  adminUpdatePartner,
  adminUploadPartnerCape,
  answerInvite,
  apiCodeStatus,
  apiCreateCodes,
  apiQuota,
  authenticatePartnerKey,
  batchCsv,
  blockBatch,
  countClick,
  countDownload,
  createBatch,
  createRequest,
  endPartnerSupport,
  inviteMember,
  listPartners,
  memberRequestImage,
  myPartnerState,
  partnerDashboard,
  PARTNER_SWITCH_LOCK_MS,
  publicPartner,
  removeMember,
  rotateApiKey,
  sanitizeLogo,
  selectableCapes,
  updateMember,
  uploadLogo,
  uploadPartnerCape,
  _internal,
} from '../server/lib/partners'
import { redeemAnyCode } from '../server/lib/redeem-route'
import { decodeRgba } from '../server/lib/png'
import type { PingDeps } from '../server/lib/serverping'
import { ownerStaff, setMemberRoles, teamOf, type Staff } from '../server/lib/team'
import { callRoute } from './circuithelpers'
import { ADMIN, login, makeEnv, seedCosmeticFixtures, seedFixtures, solidPng, type TestEnv } from './helpers'
import redeemRoute from '../server/routes/v1/me/codes/redeem.post'

const OWNER = ownerStaff(ADMIN)
const DAY = 86_400_000
const MIN = 60_000

async function player(env: TestEnv, name: string): Promise<{ uuid: string, token: string }> {
  const r = await login(env, name)
  return { uuid: r.user.uuid, token: r.token }
}

/** Partner mit Inhaber, festem Code und Kontingent; ordnet optional den Code-Umhang `emerald` zu. */
async function partner(env: TestEnv, slug = 'craftinghosting', opts: { code?: string, prefix?: string, quota?: 0 | 500 | 1000, owner?: string, items?: boolean } = {}) {
  const owner = opts.owner ?? (await player(env, `Own${slug.slice(0, 8).replace(/-/g, '')}`)).uuid
  adminCreatePartner(env.ctx, OWNER, {
    name: slug === 'craftinghosting' ? 'CraftingHosting' : slug,
    slug,
    category: 'hosting',
    code: opts.code ?? 'CRAFTING',
    prefix: opts.prefix ?? 'CH',
    owner,
    quota: opts.quota ?? 1000,
  })
  if (opts.items !== false) {
    seedFixtures(env)
    adminAddItem(env.ctx, OWNER, slug, { kind: 'cape', id: 'emerald' })
  }
  return { owner }
}

function err(fn: () => unknown): ApiError {
  try {
    fn()
  } catch (e) {
    return e as ApiError
  }
  throw new Error('expected an error')
}

async function aerr(p: Promise<unknown>): Promise<ApiError> {
  try {
    await p
  } catch (e) {
    return e as ApiError
  }
  throw new Error('expected an error')
}

// ---------------------------------------------------------------- Einlösen

describe('unified redeem (§44.2)', () => {
  it('dispatches creator → partner fixed → partner one-time → cape, else 404 code_not_found', async () => {
    const env = makeEnv()
    const { owner } = await partner(env)
    const fan = await player(env, 'Fan')
    await player(env, 'Pixelmaus')
    const creator = adminCreateCreator(env.ctx, OWNER, { player: 'Pixelmaus', tier: 'starter' }).creator
    expect(redeemAnyCode(env.ctx, fan.uuid, creator.mainCode!.toLowerCase()).kind).toBe('creator')

    const p = redeemAnyCode(env.ctx, fan.uuid, ' crafting ')
    expect(p.kind).toBe('partner')
    if (p.kind !== 'partner') throw new Error()
    expect(p.oneTime).toBe(false)
    expect(p.supportChanged).toBe(true)
    expect(p.partner.slug).toBe('craftinghosting')
    expect(p.granted.capes.map((c) => c.capeId)).toEqual(['emerald'])
    expect(one(env.ctx.db, "SELECT source FROM user_capes WHERE uuid = ? AND cape_id = 'emerald'", fan.uuid)).toEqual({ source: 'partner' })

    const batch = createBatch(env.ctx, owner, 'craftinghosting', { label: 'Test', count: 1 })
    const other = await player(env, 'Other')
    const o = redeemAnyCode(env.ctx, other.uuid, batch.codes[0]!.toLowerCase())
    expect(o.kind === 'partner' && o.oneTime).toBe(true)

    const cape = createCodes(env.ctx, ADMIN, { capeId: 'team', maxUses: 1, count: 1 }).codes[0]!.code
    const c = redeemAnyCode(env.ctx, fan.uuid, cape)
    expect(c.kind).toBe('cape')
    expect(c.kind === 'cape' && c.itemKind).toBe('cape')

    for (const bad of ['NOPE1', 'ZZ-2345-6789', 'AAAAA-BBBBB-CCCCC-DDDDD', '']) {
      expect(err(() => redeemAnyCode(env.ctx, fan.uuid, bad)).code).toBe('code_not_found')
    }
  })

  it('shares one brute-force counter (5 failures per account → 429, also for valid codes)', async () => {
    const env = makeEnv()
    await partner(env)
    const fan = await player(env, 'Guesser')
    const call = (code: string) => callRoute(env, redeemRoute, {
      method: 'POST',
      url: '/v1/me/codes/redeem',
      headers: { authorization: `Bearer ${fan.token}`, 'content-type': 'application/json' },
      body: JSON.stringify({ code }),
    })
    for (let i = 0; i < 5; i++) expect((await call(`WRONG${i}`)).error?.code).toBe('code_not_found')
    const blocked = await call('CRAFTING')
    expect(blocked.status).toBe(429)
    env.clock.advance(16 * MIN)
    const ok = await call('CRAFTING')
    expect(ok.status).toBe(200)
    expect((ok.body as { kind: string }).kind).toBe('partner')
  })

  it('counts a used one-time code (410) as a failure but not a valid one', async () => {
    const env = makeEnv()
    const { owner } = await partner(env)
    const a = await player(env, 'First')
    const b = await player(env, 'Second')
    const codes = createBatch(env.ctx, owner, 'craftinghosting', { label: 'x', count: 1 }).codes
    redeemAnyCode(env.ctx, a.uuid, codes[0]!)
    const r = await callRoute(env, redeemRoute, {
      method: 'POST',
      url: '/v1/me/codes/redeem',
      headers: { authorization: `Bearer ${b.token}`, 'content-type': 'application/json' },
      body: JSON.stringify({ code: codes[0] }),
    })
    expect(r.error?.code).toBe('partner_code_used')
    expect(env.ctx.limiter.check(`redeem-fail:${b.uuid}`, { limit: 5, windowMs: 15 * MIN }).ok).toBe(true)
  })
})

describe('partner support + 30-day lock', () => {
  it('locks switching for 30 days, still grants cosmetics, keeps the start for the same partner', async () => {
    const env = makeEnv()
    await partner(env)
    seedCosmeticFixtures(env)
    await partner(env, 'netzwerk', { code: 'NETZWERK', prefix: 'NW', items: false })
    adminAddItem(env.ctx, OWNER, 'netzwerk', { kind: 'cosmetic', id: 'code_wings' })
    const fan = await player(env, 'Fan')
    const first = redeemAnyCode(env.ctx, fan.uuid, 'CRAFTING')
    expect(first.kind === 'partner' && first.switchAvailableAt).toBe(new Date(env.clock.t + PARTNER_SWITCH_LOCK_MS).toISOString())
    const started = env.clock.t

    env.clock.advance(5 * DAY)
    const second = redeemAnyCode(env.ctx, fan.uuid, 'NETZWERK')
    if (second.kind !== 'partner') throw new Error()
    expect(second.supportChanged).toBe(false)
    expect(second.granted.cosmetics.map((c) => c.id)).toEqual(['code_wings'])
    expect(myPartnerState(env.ctx, fan.uuid).support.partner?.slug).toBe('craftinghosting')

    // Gleicher Partner erneut: idempotent, Start bleibt.
    const again = redeemAnyCode(env.ctx, fan.uuid, 'CRAFTING')
    expect(again.kind === 'partner' && again.supportChanged).toBe(false)
    expect(myPartnerState(env.ctx, fan.uuid).support.startedAt).toBe(new Date(started).toISOString())

    env.clock.advance(26 * DAY)
    const switched = redeemAnyCode(env.ctx, fan.uuid, 'NETZWERK')
    expect(switched.kind === 'partner' && switched.supportChanged).toBe(true)
    const me = myPartnerState(env.ctx, fan.uuid)
    expect(me.support.partner?.slug).toBe('netzwerk')
    expect(me.owned.map((o) => `${o.kind}:${o.id}:${o.partner?.slug}`).sort()).toEqual(['cape:emerald:craftinghosting', 'cosmetic:code_wings:netzwerk'])
  })

  it('re-supporting the last partner keeps the start; a paused partner never holds the lock', async () => {
    const env = makeEnv()
    await partner(env)
    await partner(env, 'netzwerk', { code: 'NETZWERK', prefix: 'NW', items: false })
    const fan = await player(env, 'Fan')
    redeemAnyCode(env.ctx, fan.uuid, 'CRAFTING')
    endPartnerSupport(env.ctx, fan.uuid)
    expect(myPartnerState(env.ctx, fan.uuid).support.partner).toBeNull()
    env.clock.advance(DAY)
    // Nach dem Beenden: andere Partner weiter gesperrt, derselbe sofort wieder.
    expect((redeemAnyCode(env.ctx, fan.uuid, 'NETZWERK') as { supportChanged: boolean }).supportChanged).toBe(false)
    expect((redeemAnyCode(env.ctx, fan.uuid, 'CRAFTING') as { supportChanged: boolean }).supportChanged).toBe(true)
    adminUpdatePartner(env.ctx, OWNER, 'craftinghosting', { state: 'paused' })
    // Pausiert: Codes unbekannt, Sperre weg.
    expect(err(() => redeemAnyCode(env.ctx, fan.uuid, 'CRAFTING')).code).toBe('code_not_found')
    expect((redeemAnyCode(env.ctx, fan.uuid, 'NETZWERK') as { supportChanged: boolean }).supportChanged).toBe(true)
    expect(listPartners(env.ctx).partners.map((p) => p.slug)).toEqual(['netzwerk'])
    expect(err(() => publicPartner(env.ctx, 'craftinghosting')).code).toBe('partner_not_found')
  })
})

// ---------------------------------------------------------------- Einmal-Codes + API

describe('one-time codes, quota and partner API', () => {
  it('are usable by one player ever; own re-redeem is idempotent; blocked codes stay used up', async () => {
    const env = makeEnv()
    const { owner } = await partner(env)
    const r = createBatch(env.ctx, owner, 'craftinghosting', { label: 'Gewinnspiel', count: 3 })
    expect(r.codes).toHaveLength(3)
    for (const c of r.codes) expect(c).toMatch(/^CH-[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}$/)
    const a = await player(env, 'Alpha')
    const b = await player(env, 'Beta')
    redeemAnyCode(env.ctx, a.uuid, r.codes[0]!)
    expect((redeemAnyCode(env.ctx, a.uuid, r.codes[0]!) as { oneTime: boolean }).oneTime).toBe(true)
    expect(err(() => redeemAnyCode(env.ctx, b.uuid, r.codes[0]!)).code).toBe('partner_code_used')
    expect(blockBatch(env.ctx, owner, 'craftinghosting', r.batch.id).blocked).toBe(2)
    expect(err(() => redeemAnyCode(env.ctx, b.uuid, r.codes[1]!)).code).toBe('partner_code_used')
    // Gesperrte gehen nicht ans Kontingent zurück.
    expect(partnerDashboard(env.ctx, owner, 'craftinghosting').quota).toMatchObject({ total: 1000, used: 3, free: 997 })
    const csv = batchCsv(env.ctx, owner, 'craftinghosting', r.batch.id).csv
    expect(csv.split('\r\n')[0]).toBe('code,ref,status,redeemed_at')
    expect(csv).toContain(',redeemed,')
    expect(csv).not.toContain(a.uuid)
    expect(csv.match(/,blocked,/g)).toHaveLength(2)
  })

  it('refuses batches over quota and sends quota.low once', async () => {
    const env = makeEnv()
    const { owner } = await partner(env, 'craftinghosting', { quota: 500 })
    env.ctx.partnerPingDeps = fakeDeps(['93.184.216.34'])
    await saveWebhook(env.ctx, partnerId(env, 'craftinghosting'), { url: 'https://hooks.example.com/trs', events: ['quota.low'] })
    createBatch(env.ctx, owner, 'craftinghosting', { label: 'a', count: 451 })
    createBatch(env.ctx, owner, 'craftinghosting', { label: 'b', count: 10 })
    expect(err(() => createBatch(env.ctx, owner, 'craftinghosting', { label: 'c', count: 40 })).code).toBe('partner_quota')
    const low = all<{ body: string }>(env.ctx.db, "SELECT body FROM partner_deliveries WHERE event = 'quota.low'")
    expect(low).toHaveLength(1)
    expect(JSON.parse(low[0]!.body)).toMatchObject({ type: 'quota.low', total: 500, used: 451 })
    expect(err(() => createBatch(env.ctx, owner, 'craftinghosting', { label: 'd', count: 501 })).code).toBe('invalid_request')
    // Team gibt ein Paket frei; ein offener Nachschub-Antrag wird geschlossen.
    createRequest(env.ctx, owner, 'craftinghosting', { kind: 'quota', amount: 1000 })
    expect(apiQuota(env.ctx, row(env, 'craftinghosting')).requested).toBe(true)
    adminGrantQuota(env.ctx, OWNER, 'craftinghosting', { amount: 1000 })
    expect(apiQuota(env.ctx, row(env, 'craftinghosting'))).toEqual({ total: 1500, used: 461, requested: false })
  })

  it('partner API: hashed key, ref idempotency, status without player data, paused → 403', async () => {
    const env = makeEnv()
    const { owner } = await partner(env)
    const key = rotateApiKey(env.ctx, owner, 'craftinghosting').key
    expect(key).toMatch(/^trsp_live_[A-Za-z0-9]{32}$/)
    expect(JSON.stringify(all(env.ctx.db, 'SELECT * FROM partner_api_keys'))).not.toContain(key)
    expect(err(() => authenticatePartnerKey(env.ctx, 'Bearer trsp_live_' + 'x'.repeat(32), '1.2.3.4')).status).toBe(401)
    const p = authenticatePartnerKey(env.ctx, `Bearer ${key}`, '1.2.3.4')

    const one1 = apiCreateCodes(env.ctx, p, { count: 1, label: 'Bestellungen', ref: 'order-48213' })
    const one2 = apiCreateCodes(env.ctx, p, { count: 1, label: 'Bestellungen', ref: 'order-48213' })
    expect(one2.codes).toEqual(one1.codes)
    expect(one2.quota.used).toBe(1)
    expect(err(() => apiCreateCodes(env.ctx, p, { count: 2, ref: 'x' })).code).toBe('invalid_request')
    const many = apiCreateCodes(env.ctx, p, { count: 5, label: 'Bestellungen' })
    expect(many.quota).toEqual({ total: 1000, used: 6 })
    expect(partnerDashboard(env.ctx, owner, 'craftinghosting').batches!.filter((b) => b.via === 'api').map((b) => b.count)).toEqual([6])

    const fan = await player(env, 'Kunde')
    redeemAnyCode(env.ctx, fan.uuid, one1.codes[0]!)
    const st = apiCodeStatus(env.ctx, p, one1.codes[0]!)
    expect(st).toMatchObject({ redeemed: true, blocked: false, ref: 'order-48213' })
    expect(JSON.stringify(st)).not.toContain(fan.uuid)

    // Fremder Code → 404.
    await partner(env, 'netzwerk', { code: 'NETZWERK', prefix: 'NW', items: false })
    const other = createBatch(env.ctx, row(env, 'netzwerk').owner_uuid!, 'netzwerk', { label: 'x', count: 1 }).codes[0]!
    expect(err(() => apiCodeStatus(env.ctx, p, other)).code).toBe('code_not_found')
    expect(err(() => apiCreateCodes(env.ctx, p, { count: 2000 })).code).toBe('invalid_request')

    adminUpdatePartner(env.ctx, OWNER, 'craftinghosting', { state: 'paused' })
    expect(err(() => authenticatePartnerKey(env.ctx, `Bearer ${key}`, '1.2.3.4')).code).toBe('partner_paused')
  })

  it('generates codes from the documented alphabet and keys in base62', () => {
    const seen = new Set<string>()
    for (let i = 0; i < 2000; i++) {
      const c = _internal.newOneTimeCode('CH')
      expect(c).toMatch(/^CH-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}$/)
      seen.add(c)
    }
    expect(seen.size).toBe(2000)
    expect(_internal.newApiKey()).toMatch(/^trsp_live_[0-9A-Za-z]{32}$/)
  })
})

// ---------------------------------------------------------------- Code-Namensraum

describe('code namespace (creator vs partner)', () => {
  it('creator codes refuse partner fixed codes and the one-time shape; partners refuse creator codes', async () => {
    const env = makeEnv()
    await partner(env)
    await player(env, 'Pixel')
    const c = adminCreateCreator(env.ctx, OWNER, { player: 'Pixel', tier: 'partner' }).creator
    const pixel = c.uuid
    expect(err(() => createExtraCode(env.ctx, pixel, { code: 'CRAFTING' })).code).toBe('creator_code_taken')
    expect(err(() => adminUpdateCreator(env.ctx, OWNER, pixel, { mainCode: 'CRAFTING' })).code).toBe('creator_code_taken')
    expect(normalizeCreatorCode('CH-2345-6789')).toBeNull()
    expect(err(() => createExtraCode(env.ctx, pixel, { code: 'CH-2345-6789' })).code).toBe('invalid_code')
    // Partner anlegen mit einem Creator-Code → 409.
    const own = (await player(env, 'NewOwner')).uuid
    expect(err(() => adminCreatePartner(env.ctx, OWNER, { name: 'X', slug: 'xyz', category: 'other', code: c.mainCode!, prefix: 'XY', owner: own })).code).toBe('code_taken')
    expect(err(() => adminCreatePartner(env.ctx, OWNER, { name: 'X', slug: 'xyz', category: 'other', code: 'CRAFTING', prefix: 'XY', owner: own })).code).toBe('code_taken')
    expect(err(() => adminCreatePartner(env.ctx, OWNER, { name: 'X', slug: 'xyz', category: 'other', code: 'NEWCODE', prefix: 'CH', owner: own })).code).toBe('prefix_taken')
    expect(err(() => adminCreatePartner(env.ctx, OWNER, { name: 'X', slug: 'servers', category: 'other', code: 'NEWCODE', prefix: 'XY', owner: own })).code).toBe('invalid_request')
    // 20 Zeichen ohne Bindestrich bleiben den Umhang-Codes.
    expect(err(() => adminCreatePartner(env.ctx, OWNER, { name: 'X', slug: 'xyz', category: 'other', code: 'A'.repeat(20), prefix: 'XY', owner: own })).code).toBe('invalid_request')
    // Code-Änderung per Antrag: belegter Creator-Code → 409 schon beim Antrag.
    const owner = row(env, 'craftinghosting').owner_uuid!
    expect(err(() => createRequest(env.ctx, owner, 'craftinghosting', { kind: 'code_change', code: c.mainCode! })).code).toBe('code_taken')
  })
})

// ---------------------------------------------------------------- Partner-Server

function partnerId(env: TestEnv, slug: string): number {
  return one<{ id: number }>(env.ctx.db, 'SELECT id FROM partners WHERE slug = ?', slug)!.id
}
function row(env: TestEnv, slug: string) {
  return one<{ id: number, owner_uuid: string | null, slug: string, name: string, state: string, prefix: string } & Record<string, never>>(env.ctx.db, 'SELECT * FROM partners WHERE slug = ?', slug) as never as Parameters<typeof apiQuota>[1]
}

/** Antrag stellen + annehmen → freigegebener Server. */
async function approvedServer(env: TestEnv, slug: string, owner: string, over: Partial<{ address: string, minVersion: string, maxVersion: string }> = {}) {
  adminUpdatePartner(env.ctx, OWNER, slug, { serverAllowed: true })
  createRequest(env.ctx, owner, slug, {
    kind: 'server',
    name: `${slug} Lobby`,
    address: over.address ?? `lobby.${slug}.example.com`,
    minVersion: over.minVersion ?? '1.8',
    maxVersion: over.maxVersion ?? '1.21.11',
    motd: '§bKostenloser Server§r in 2 Minuten – teste hier und schau dir alles in Ruhe an, wirklich alles',
  })
  const req = adminListRequests(env.ctx, OWNER, {}).requests.find((r) => r.kind === 'server' && r.partner.slug === slug)!
  adminDecideRequest(env.ctx, OWNER, req.id, { status: 'accepted' })
  return one<{ id: string }>(env.ctx.db, 'SELECT s.id FROM partner_servers s JOIN partners p ON p.id = s.partner_id WHERE p.slug = ?', slug)!.id
}

function fakeDeps(ips: string[], connect?: PingDeps['connect']): PingDeps {
  return {
    resolveSrv: async () => null,
    lookup: async () => ips,
    connect: connect ?? (() => {
      throw new Error('should not connect')
    }),
  }
}

const servers: Server[] = []
afterEach(() => {
  for (const s of servers.splice(0)) s.close()
})

async function fakeMc(): Promise<number> {
  const server = createServer((sock) => {
    sock.once('data', () => {
      const s = Buffer.from(JSON.stringify({ version: { name: '1.21.11', protocol: 774 }, players: { online: 38, max: 200 }, description: 'Hi' }), 'utf8')
      const v = (n: number) => {
        const out: number[] = []
        do {
          let b = n & 0x7f
          n >>>= 7
          if (n) b |= 0x80
          out.push(b)
        } while (n)
        return Buffer.from(out)
      }
      const body = Buffer.concat([v(0), v(s.length), s])
      sock.end(Buffer.concat([v(body.length), body]))
    })
  })
  servers.push(server)
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
  return (server.address() as AddressInfo).port
}

describe('partner servers (§44.3)', () => {
  it('compares and parses Minecraft versions', () => {
    expect(compareVersions('1.8', '1.8.9')).toBe(-1)
    expect(compareVersions('1.21.11', '1.21.9')).toBe(1)
    expect(compareVersions('26.1', '1.21.11')).toBe(1)
    expect(compareVersions('1.21', '1.21.0')).toBe(0)
    expect(parseMcVersion('1.21.11-pre1')).toBe('1.21.11')
    expect(parseMcVersion('banana')).toBeNull()
  })

  it('lists approved, online, matching servers of active partners: max 3, daily order, motd clean', async () => {
    const env = makeEnv()
    const ids: string[] = []
    for (const [i, slug] of ['alpha', 'bravo', 'charlie', 'delta', 'echo'].entries()) {
      const { owner } = await partner(env, slug, { code: `CODE${slug.toUpperCase()}`, prefix: `P${'ABCDE'[i]}`, items: false })
      ids.push(await approvedServer(env, slug, owner, slug === 'echo' ? { minVersion: '1.20', maxVersion: '1.21.11' } : {}))
    }
    // Noch nie gepingt → nichts.
    expect(listPartnerServers(env.ctx, '1.21.11').servers).toEqual([])
    run(env.ctx.db, 'UPDATE partner_servers SET online = 1, last_online_at = ?', env.clock.t)
    const all5 = listPartnerServers(env.ctx)
    expect(all5.ttl).toBe(600)
    expect(all5.servers).toHaveLength(3)
    expect(all5.servers[0]!.motd.startsWith('Kostenloser Server in 2 Minuten – teste hier')).toBe(true)
    expect(all5.servers[0]!.motd.length).toBeLessThanOrEqual(60)
    // Version: 1.8.9 → echo (ab 1.20) fällt weg.
    const old = listPartnerServers(env.ctx, '1.8.9').servers.map((s) => s.partner.slug)
    expect(old).not.toContain('echo')
    expect(listPartnerServers(env.ctx, '1.7.10').servers).toEqual([])
    // Tägliche Rotation: gleich innerhalb eines Tages, wechselt über die Tage.
    const today = listPartnerServers(env.ctx).servers.map((s) => s.id)
    env.clock.advance(MIN)
    run(env.ctx.db, 'UPDATE partner_servers SET last_online_at = ?', env.clock.t)
    expect(listPartnerServers(env.ctx).servers.map((s) => s.id)).toEqual(today)
    const orders = new Set<string>()
    for (let d = 0; d < 10; d++) {
      env.clock.advance(DAY)
      run(env.ctx.db, 'UPDATE partner_servers SET last_online_at = ?', env.clock.t)
      orders.add(listPartnerServers(env.ctx).servers.map((s) => s.id).join(','))
    }
    expect(orders.size).toBeGreaterThan(1)
    // Offline > 30 min, ausgeblendet oder pausiert → weg.
    run(env.ctx.db, "UPDATE partner_servers SET last_online_at = ? WHERE id = ?", env.clock.t - 31 * MIN, ids[0]!)
    adminUpdatePartner(env.ctx, OWNER, 'bravo', { state: 'paused' })
    run(env.ctx.db, 'UPDATE partner_servers SET hidden = 1 WHERE id = ?', ids[2]!)
    expect(listPartnerServers(env.ctx).servers.map((s) => s.partner.slug).sort()).toEqual(['delta', 'echo'])
  })

  it('pings with the SSRF guard (private addresses never contacted) and counts joins once per day', async () => {
    const env = makeEnv()
    const { owner } = await partner(env, 'alpha', { code: 'ALPHA', prefix: 'AL', items: false })
    const id = await approvedServer(env, 'alpha', owner, { address: 'internal.example.com' })
    expect(await pingPartnerServers(env.ctx, fakeDeps(['10.0.0.5']))).toBe(0)
    expect(one(env.ctx.db, 'SELECT online, status_reason FROM partner_servers WHERE id = ?', id)).toEqual({ online: 0, status_reason: 'private_address' })
    expect(await pingPartnerServers(env.ctx, fakeDeps(['169.254.169.254']))).toBe(0)
    // Erreichbarer Server (Test erlaubt Loopback), Port wird übernommen.
    const port = await fakeMc()
    run(env.ctx.db, 'UPDATE partner_servers SET address = ? WHERE id = ?', `mc.example.com:${port}`, id)
    const deps: PingDeps = { ...fakeDeps(['127.0.0.1'], (ip, p) => new Socket().connect({ host: ip, port: p })), allowPrivate: true }
    expect(await pingPartnerServers(env.ctx, deps)).toBe(1)
    expect(one(env.ctx.db, 'SELECT online, players_online, last_online_at FROM partner_servers WHERE id = ?', id)).toEqual({ online: 1, players_online: 38, last_online_at: env.clock.t })
    expect(listPartnerServers(env.ctx, '1.21.11').servers.map((s) => s.id)).toEqual([id])

    const fan = await player(env, 'Joiner')
    expect(recordServerJoin(env.ctx, id, fan.uuid)).toBe(true)
    expect(recordServerJoin(env.ctx, id, fan.uuid)).toBe(false)
    expect(recordServerJoin(env.ctx, id, null)).toBe(false)
    env.clock.advance(DAY)
    expect(recordServerJoin(env.ctx, id, fan.uuid)).toBe(true)
    expect(serverJoinStats(env.ctx, id, 7)).toEqual({ joins: 2, distinct: 1 })
    expect(JSON.stringify(all(env.ctx.db, 'SELECT * FROM partner_server_joins'))).not.toContain(fan.uuid)
  })
})

// ---------------------------------------------------------------- Webhooks

describe('webhooks (§44.6)', () => {
  it('signs as documented and resolves only public addresses', async () => {
    const sig = signWebhook('whsec_test', 1763719924, '{"type":"test.ping"}')
    expect(sig).toBe(`sha256=${createHmac('sha256', 'whsec_test').update('1763719924.{"type":"test.ping"}').digest('hex')}`)
    for (const ips of [['10.0.0.1'], ['127.0.0.1'], ['100.64.1.1'], ['93.184.216.34', '192.168.1.1'], ['::1']]) {
      expect((await aerr(resolvePublic('hooks.example.com', { lookup: async () => ips }))) as unknown as { code: string }).toMatchObject({ code: 'private_address' })
    }
    expect(await resolvePublic('hooks.example.com', { lookup: async () => ['93.184.216.34'] })).toBe('93.184.216.34')
    expect(((await aerr(resolvePublic('localhost', { lookup: async () => ['93.184.216.34'] }))) as unknown as { code: string }).code).toBe('private_address')

    const env = makeEnv()
    env.ctx.partnerPingDeps = fakeDeps(['192.168.0.10'])
    expect((await aerr(checkWebhookUrl(env.ctx, 'https://intranet.example.com/hook'))).code).toBe('invalid_webhook_url')
  })

  it('delivers signed JSON without player data and retries after 1, 5, 30 min, 2 h, 6 h, 24 h, then fails', async () => {
    const env = makeEnv()
    const { owner } = await partner(env)
    env.ctx.partnerPingDeps = fakeDeps(['93.184.216.34'])
    const pid = partnerId(env, 'craftinghosting')
    const saved = await saveWebhook(env.ctx, pid, { url: 'https://hooks.example.com/trs', events: ['code.redeemed', 'partner.supported'] })
    expect(saved.secret).toMatch(/^whsec_/)
    const sent: { url: string, headers: Record<string, string>, body: string }[] = []
    let status = 500
    const sender: WebhookSender = async (url, headers, body) => {
      sent.push({ url, headers, body })
      return { status, body: 'x'.repeat(500) }
    }
    env.ctx.partnerWebhookSend = sender

    const fan = await player(env, 'Kunde')
    const code = createBatch(env.ctx, owner, 'craftinghosting', { label: 'x', count: 1 }).codes[0]!
    redeemAnyCode(env.ctx, fan.uuid, code)
    expect(all<{ event: string }>(env.ctx.db, 'SELECT event FROM partner_deliveries ORDER BY id').map((d) => d.event)).toEqual(['partner.supported', 'code.redeemed'])
    await processDeliveries(env.ctx)
    expect(sent).toHaveLength(2)
    for (const s of sent) {
      expect(s.body).not.toContain(fan.uuid)
      expect(s.body).not.toContain('Kunde')
      expect(s.headers['User-Agent']).toBe('TRS-Webhooks/1')
      expect(s.headers['X-TRS-Signature']).toBe(signWebhook(saved.secret!, Number(s.headers['X-TRS-Timestamp']), s.body))
    }
    expect(JSON.parse(sent[1]!.body)).toMatchObject({ type: 'code.redeemed', code, ref: null })
    expect(JSON.parse(sent[0]!.body)).toMatchObject({ type: 'partner.supported', supporters: 1 })

    const id = one<{ id: number }>(env.ctx.db, "SELECT id FROM partner_deliveries WHERE event = 'code.redeemed'")!.id
    const d0 = one<{ status: string, attempts: number, next_at: number, response: string }>(env.ctx.db, 'SELECT status, attempts, next_at, response FROM partner_deliveries WHERE id = ?', id)!
    expect(d0).toMatchObject({ status: 'pending', attempts: 1, next_at: env.clock.t + RETRY_DELAYS_MS[0] })
    expect(d0.response).toHaveLength(200)
    for (let i = 1; i < RETRY_DELAYS_MS.length; i++) {
      env.clock.advance(RETRY_DELAYS_MS[i - 1]! - 1000)
      expect(await processDeliveries(env.ctx)).toBe(0)
      env.clock.advance(1000)
      expect(await processDeliveries(env.ctx)).toBe(2)
      expect(one(env.ctx.db, 'SELECT next_at FROM partner_deliveries WHERE id = ?', id)).toEqual({ next_at: env.clock.t + RETRY_DELAYS_MS[i] })
    }
    env.clock.advance(RETRY_DELAYS_MS[RETRY_DELAYS_MS.length - 1]!)
    await processDeliveries(env.ctx)
    expect(one(env.ctx.db, 'SELECT status, attempts, next_at FROM partner_deliveries WHERE id = ?', id)).toEqual({ status: 'failed', attempts: 7, next_at: null })

    // Manuell: genau ein Versuch.
    const again = await retryDelivery(env.ctx, pid, id)
    expect(again.status).toBe('failed')
    status = 204
    const ok = await retryDelivery(env.ctx, pid, id)
    expect(ok).toMatchObject({ status: 'delivered', lastStatus: 204 })
    // Nicht gewählte Ereignisse kommen nicht in die Warteschlange; test.ping immer.
    expect(queueWebhook(env.ctx, pid, 'quota.low', { total: 1, used: 1 })).toBeNull()
    expect((await sendTestWebhook(env.ctx, pid)).status).toBe('delivered')
  })

  it('keeps pending deliveries across restarts (persisted) and refuses private targets at delivery time', async () => {
    const env = makeEnv()
    await partner(env)
    env.ctx.partnerPingDeps = fakeDeps(['93.184.216.34'])
    const pid = partnerId(env, 'craftinghosting')
    await saveWebhook(env.ctx, pid, { url: 'https://hooks.example.com/trs', events: [] })
    const id = queueWebhook(env.ctx, pid, 'test.ping')!
    // Standard-Versand mit DNS, das inzwischen auf eine private Adresse zeigt (DNS-Rebinding) → kein Verbinden.
    env.ctx.partnerPingDeps = fakeDeps(['10.1.2.3'])
    const v = await deliverOne(env.ctx, id)
    expect(v).toMatchObject({ status: 'pending', error: 'private_address', attempts: 1 })
  })
})

// ---------------------------------------------------------------- Rechte, Team-Modus, Anträge

describe('dashboard rights and team mode', () => {
  it('members need the right; only the owner invites; team mode is audited', async () => {
    const env = makeEnv()
    const { owner } = await partner(env)
    const helper = await player(env, 'Helper')
    const stranger = await player(env, 'Stranger')
    const mod = await player(env, 'TeamMod')
    expect(err(() => partnerDashboard(env.ctx, stranger.uuid, 'craftinghosting')).code).toBe('partner_forbidden')
    inviteMember(env.ctx, owner, 'craftinghosting', { name: 'Helper', rights: ['stats'] })
    // Noch nicht angenommen → kein Zugang.
    expect(err(() => partnerDashboard(env.ctx, helper.uuid, 'craftinghosting')).code).toBe('partner_forbidden')
    expect(myPartnerState(env.ctx, helper.uuid).invites.map((i) => i.slug)).toEqual(['craftinghosting'])
    answerInvite(env.ctx, helper.uuid, 'craftinghosting', { accept: true })
    const view = partnerDashboard(env.ctx, helper.uuid, 'craftinghosting')
    expect(view.viewer).toEqual({ owner: false, team: false, rights: ['stats'] })
    expect(view.stats).not.toBeNull()
    expect(view.batches).toBeNull()
    expect(view.webhook).toBeNull()
    expect(view.members).toBeNull()
    expect(err(() => createBatch(env.ctx, helper.uuid, 'craftinghosting', { label: 'x', count: 1 })).code).toBe('partner_right')
    expect(err(() => rotateApiKey(env.ctx, helper.uuid, 'craftinghosting')).code).toBe('partner_right')
    expect(err(() => inviteMember(env.ctx, helper.uuid, 'craftinghosting', { uuid: stranger.uuid, rights: ['stats'] })).code).toBe('partner_right')
    expect(myPartnerState(env.ctx, helper.uuid).member).toEqual([{ slug: 'craftinghosting', name: 'CraftingHosting', rights: ['stats'], owner: false }])
    updateMember(env.ctx, owner, 'craftinghosting', helper.uuid, { rights: ['stats', 'codes'] })
    expect(createBatch(env.ctx, helper.uuid, 'craftinghosting', { label: 'x', count: 1 }).codes).toHaveLength(1)
    expect(all(env.ctx.db, "SELECT action FROM admin_log WHERE action = 'partner.member.batch.create'")).toHaveLength(1)

    // Team-Modus: nur mit partners.manage.
    setMemberRoles(env.ctx, OWNER, mod.uuid, ['moderator'])
    expect(err(() => partnerDashboard(env.ctx, mod.uuid, 'craftinghosting')).code).toBe('partner_forbidden')
    expect(err(() => adminListPartners(env.ctx, teamOf(env.ctx, mod.uuid)!)).status).toBe(403)
    setMemberRoles(env.ctx, OWNER, mod.uuid, ['admin'])
    const team = partnerDashboard(env.ctx, mod.uuid, 'craftinghosting')
    expect(team.viewer.team).toBe(true)
    expect(team.members).not.toBeNull()
    addCampaign(env.ctx, mod.uuid, 'craftinghosting', { campaign: 'youtube' })
    expect(all(env.ctx.db, "SELECT action, target FROM admin_log WHERE action = 'partner.team.link.add'")).toEqual([{ action: 'partner.team.link.add', target: 'partner:craftinghosting' }])
    expect(partnerDashboard(env.ctx, owner, 'craftinghosting').teamChanges.map((c) => c.action)).toContain('link.add')
    expect(err(() => inviteMember(env.ctx, mod.uuid, 'craftinghosting', { uuid: stranger.uuid, rights: ['stats'] })).code).toBe('partner_team_forbidden')
    removeMember(env.ctx, mod.uuid, 'craftinghosting', helper.uuid)
    expect(err(() => partnerDashboard(env.ctx, helper.uuid, 'craftinghosting')).code).toBe('partner_forbidden')
  })

  it('requests: one open per kind (promo per channel), old version stays live until accepted', async () => {
    const env = makeEnv()
    const { owner } = await partner(env)
    createRequest(env.ctx, owner, 'craftinghosting', { kind: 'profile', text: { de: 'Neu', en: 'New' }, links: [{ label: 'Web', url: 'https://craftinghosting.example' }] })
    expect(err(() => createRequest(env.ctx, owner, 'craftinghosting', { kind: 'profile', text: { de: 'x', en: 'y' }, links: [] })).code).toBe('partner_request_open')
    expect(err(() => createRequest(env.ctx, owner, 'craftinghosting', { kind: 'profile', text: { de: 'x', en: 'y' }, links: [{ label: 'X', url: 'http://insecure.example' }] })).code).toBe('invalid_request')
    createRequest(env.ctx, owner, 'craftinghosting', { kind: 'promo', channel: 'blog', topic: 'Neu', text: 'Text' })
    createRequest(env.ctx, owner, 'craftinghosting', { kind: 'promo', channel: 'newsletter', topic: 'Neu', text: 'Text' })
    expect(err(() => createRequest(env.ctx, owner, 'craftinghosting', { kind: 'promo', channel: 'blog', topic: 'x', text: 'y' })).code).toBe('partner_request_open')
    expect(publicPartner(env.ctx, 'craftinghosting').text).toEqual({ de: '', en: '' })
    const reqs = adminListRequests(env.ctx, OWNER, {}).requests
    expect(reqs).toHaveLength(3)
    expect(err(() => adminDecideRequest(env.ctx, OWNER, reqs[1]!.id, { status: 'rejected' })).code).toBe('reason_required')
    adminDecideRequest(env.ctx, OWNER, reqs[1]!.id, { status: 'rejected', reason: 'Zu werblich' })
    adminDecideRequest(env.ctx, OWNER, reqs[0]!.id, { status: 'accepted' })
    expect(publicPartner(env.ctx, 'craftinghosting')).toMatchObject({ text: { de: 'Neu', en: 'New' }, links: [{ label: 'Web', url: 'https://craftinghosting.example' }] })
    const view = partnerDashboard(env.ctx, owner, 'craftinghosting').requests
    expect(view.find((r) => r.id === reqs[1]!.id)).toMatchObject({ status: 'rejected', reason: 'Zu werblich' })
  })

  it('logo and cape uploads go through review; accepted capes become partner items (not shareable)', async () => {
    const env = makeEnv()
    const { owner } = await partner(env)
    expect(err(() => uploadLogo(env.ctx, owner, 'craftinghosting', solidPng(64, 32))).code).toBe('invalid_logo')
    const big = sanitizeLogo(solidPng(512, 512))
    expect(decodeRgba(big, 1 << 20)).toMatchObject({ width: 128, height: 128 })
    expect(decodeRgba(sanitizeLogo(solidPng(32, 32)), 1 << 20)).toMatchObject({ width: 128, height: 128 })
    uploadLogo(env.ctx, owner, 'craftinghosting', solidPng(256, 256))
    expect(publicPartner(env.ctx, 'craftinghosting').logo).toBeNull()
    const cape = uploadPartnerCape(env.ctx, owner, 'craftinghosting', solidPng(128, 64 * 4), { name: 'Würfel', frames: '4', frameTimeMs: '150' }).request
    expect(cape.data).toMatchObject({ name: 'Würfel', frames: 4, frameTimeMs: 150, width: 128, height: 64 })
    for (const r of adminListRequests(env.ctx, OWNER, {}).requests) adminDecideRequest(env.ctx, OWNER, r.id, { status: 'accepted' })
    expect(publicPartner(env.ctx, 'craftinghosting').logo).toMatch(/^\/v1\/partners\/craftinghosting\/logo\.png\?v=[0-9a-f]{12}$/)
    const items = publicPartner(env.ctx, 'craftinghosting').cosmetics
    expect(items.map((i) => i.name)).toEqual(['Smaragd', 'Würfel'])
    const capeId = items[1]!.id
    const fan = await player(env, 'Fan')
    expect(catalog(env.ctx, fan.uuid).some((c) => c.id === capeId)).toBe(false)
    redeemAnyCode(env.ctx, fan.uuid, 'CRAFTING')
    const entry = catalog(env.ctx, fan.uuid).find((c) => c.id === capeId)!
    expect(entry).toMatchObject({ owned: true, shareable: false, animated: true })
    expect(setActiveCape(env.ctx, fan.uuid, capeId)?.id).toBe(capeId)
    expect(shareRole(env.ctx, fan.uuid, one(env.ctx.db, 'SELECT * FROM capes WHERE id = ?', capeId)!)).toBeNull()
    expect(partnerDashboard(env.ctx, owner, 'craftinghosting').stats!.capeWorn).toBe(1)
  })
})

// ---------------------------------------------------------------- Werbe-Links

describe('download links (/go)', () => {
  it('counts clicks once per IP+UA in 10 min, downloads per campaign, unknown → plain download page', async () => {
    const env = makeEnv()
    const { owner } = await partner(env)
    addCampaign(env.ctx, owner, 'craftinghosting', { campaign: 'youtube' })
    const c = { ip: '1.2.3.4', ua: 'Firefox' }
    expect(countClick(env.ctx, 'craftinghosting', 'youtube', c)).toBe('/download?partner=craftinghosting&c=youtube')
    expect(countClick(env.ctx, 'craftinghosting', 'youtube', c)).toBe('/download?partner=craftinghosting&c=youtube')
    expect(countClick(env.ctx, 'craftinghosting', 'tiktok', c)).toBe('/download?partner=craftinghosting')
    expect(countClick(env.ctx, 'nope', undefined, c)).toBe('/download')
    env.clock.advance(11 * MIN)
    countClick(env.ctx, 'craftinghosting', 'youtube', c)
    expect(countDownload(env.ctx, 'craftinghosting', 'youtube', c)).toBe(true)
    expect(countDownload(env.ctx, 'craftinghosting', 'youtube', c)).toBe(false)
    const links = partnerDashboard(env.ctx, owner, 'craftinghosting').links!
    expect(links.path).toBe('/go/craftinghosting')
    expect(links.campaigns).toEqual([
      { campaign: '', clicks30d: 1, downloads30d: 0 },
      { campaign: 'youtube', clicks30d: 2, downloads30d: 1 },
    ])
    expect(JSON.stringify(all(env.ctx.db, 'SELECT * FROM partner_link_stats'))).not.toContain('1.2.3.4')
    adminUpdatePartner(env.ctx, OWNER, 'craftinghosting', { state: 'paused' })
    expect(countClick(env.ctx, 'craftinghosting', 'youtube', { ip: '5.6.7.8', ua: 'x' })).toBe('/download')
  })
})

// ---------------------------------------------------------------- Runde 2

describe('partner server switch (round 2)', () => {
  it('is off by default: no server requests, no listing, no popup server, no joins; the team switches it on', async () => {
    const env = makeEnv()
    const { owner } = await partner(env, 'alpha', { code: 'ALPHA', prefix: 'AL', items: false })
    expect(partnerDashboard(env.ctx, owner, 'alpha').serverAllowed).toBe(false)
    expect(adminListPartners(env.ctx, OWNER).partners[0]!.serverAllowed).toBe(false)
    const body = { kind: 'server', name: 'Lobby', address: 'lobby.alpha.example.com', minVersion: '1.8', maxVersion: '1.21.11', motd: 'Hi' }
    const e = err(() => createRequest(env.ctx, owner, 'alpha', body))
    expect([e.status, e.code]).toEqual([403, 'partner_server_not_allowed'])

    const id = await approvedServer(env, 'alpha', owner)
    run(env.ctx.db, 'UPDATE partner_servers SET online = 1, last_online_at = ?', env.clock.t)
    expect(listPartnerServers(env.ctx).servers.map((s) => s.id)).toEqual([id])
    expect(publicPartner(env.ctx, 'alpha').server).toEqual({ name: 'alpha Lobby', address: 'lobby.alpha.example.com' })
    const fan = await player(env, 'Joiner')
    expect(recordServerJoin(env.ctx, id, fan.uuid)).toBe(true)

    // Wieder aus: Server bleibt gespeichert, ist aber überall weg.
    createRequest(env.ctx, owner, 'alpha', { ...body, name: 'Lobby 2' })
    expect(adminUpdatePartner(env.ctx, OWNER, 'alpha', { serverAllowed: false }).partner.serverAllowed).toBe(false)
    expect(listPartnerServers(env.ctx).servers).toEqual([])
    expect(publicPartner(env.ctx, 'alpha').server).toBeNull()
    expect(listPartners(env.ctx).partners[0]!.server).toBeNull()
    env.clock.advance(DAY)
    expect(recordServerJoin(env.ctx, id, fan.uuid)).toBe(false)
    expect(err(() => createRequest(env.ctx, owner, 'alpha', body)).code).toBe('partner_server_not_allowed')
    const open = adminListRequests(env.ctx, OWNER, {}).requests.find((r) => r.kind === 'server')!
    expect(err(() => adminDecideRequest(env.ctx, OWNER, open.id, { status: 'accepted' })).code).toBe('partner_server_not_allowed')
    adminDecideRequest(env.ctx, OWNER, open.id, { status: 'rejected', reason: 'Nicht freigeschaltet' })
    expect(partnerDashboard(env.ctx, owner, 'alpha').server!.current!.name).toBe('alpha Lobby')
    // Neu angelegt mit Schalter an; „stats“ ist als Adresse reserviert.
    const o2 = (await player(env, 'OwnBeta')).uuid
    adminCreatePartner(env.ctx, OWNER, { name: 'Beta', slug: 'beta', category: 'network', code: 'BETACODE', prefix: 'BE', owner: o2, serverAllowed: true })
    expect(partnerDashboard(env.ctx, o2, 'beta').serverAllowed).toBe(true)
    expect(err(() => adminCreatePartner(env.ctx, OWNER, { name: 'S', slug: 'stats', category: 'other', code: 'STATSCODE', prefix: 'ST', owner: o2 })).code).toBe('invalid_request')
  })
})

describe('partner cape from own uploads (round 2)', () => {
  it('lists approved uploads of owner and members, sends a request, accepting copies it as partner cape', async () => {
    const env = makeEnv()
    const { owner } = await partner(env)
    const helper = await player(env, 'Helper')
    const stranger = await player(env, 'Stranger')
    inviteMember(env.ctx, owner, 'craftinghosting', { name: 'Helper', rights: ['cosmetics'] })
    answerInvite(env.ctx, helper.uuid, 'craftinghosting', { accept: true })
    const mine = uploadCape(env.ctx, owner, solidPng(64, 32, [10, 200, 10, 255]), 'Grün')
    const helpers = uploadCape(env.ctx, helper.uuid, solidPng(128, 64 * 2, [10, 10, 200, 255]), 'Blau', { frames: 2, frameTimeMs: 200 })
    const pending = uploadCape(env.ctx, owner, solidPng(64, 32, [1, 2, 3, 255]), 'Wartet')
    const foreign = uploadCape(env.ctx, stranger.uuid, solidPng(64, 32, [200, 200, 10, 255]), 'Fremd')
    run(env.ctx.db, "UPDATE capes SET status = 'approved' WHERE id IN (?, ?, ?)", mine.id, helpers.id, foreign.id)

    const list = selectableCapes(env.ctx, helper.uuid, 'craftinghosting').capes
    expect(list.map((c) => c.id).sort()).toEqual([mine.id, helpers.id].sort())
    expect(list.find((c) => c.id === helpers.id)).toMatchObject({ owner: { uuid: helper.uuid, name: 'Helper' }, frames: 2, frameTimeMs: 200, scale: 2 })
    expect(list.some((c) => c.id === pending.id)).toBe(false)
    expect(err(() => createRequest(env.ctx, owner, 'craftinghosting', { kind: 'cape', capeId: foreign.id })).code).toBe('cape_not_found')
    expect(err(() => createRequest(env.ctx, owner, 'craftinghosting', { kind: 'cape', capeId: pending.id })).code).toBe('cape_not_found')

    const first = createRequest(env.ctx, owner, 'craftinghosting', { kind: 'cape', capeId: mine.id }).request
    // Neue Wahl ersetzt den offenen Antrag.
    const req = createRequest(env.ctx, helper.uuid, 'craftinghosting', { kind: 'cape', capeId: helpers.id, name: 'CH Blau' }).request
    expect(req).toMatchObject({ kind: 'cape', status: 'open', hasImage: true, data: { source: 'select', capeId: helpers.id, name: 'CH Blau', owner: 'Helper', frames: 2 } })
    expect(partnerDashboard(env.ctx, owner, 'craftinghosting').requests.find((r) => r.id === first.id)!.status).toBe('withdrawn')
    expect(memberRequestImage(env.ctx, owner, 'craftinghosting', req.id).sha256).toBe(getCape(env.ctx, helpers.id)!.sha256)

    adminDecideRequest(env.ctx, OWNER, req.id, { status: 'accepted' })
    const items = partnerDashboard(env.ctx, owner, 'craftinghosting').cosmetics
    const added = items.find((i) => i.name === 'CH Blau')!
    expect(added.id).not.toBe(helpers.id)
    expect(added.cape).toMatchObject({ frames: 2, frameTimeMs: 200 })
    expect(getCape(env.ctx, added.id)).toMatchObject({ partner_id: expect.any(Number), owner_uuid: null, sha256: getCape(env.ctx, helpers.id)!.sha256, width: 128, height: 64 })
    // Der Upload des Mitglieds bleibt seiner.
    expect(getCape(env.ctx, helpers.id)).toMatchObject({ owner_uuid: helper.uuid, partner_id: null })
    const fan = await player(env, 'Fan')
    redeemAnyCode(env.ctx, fan.uuid, 'CRAFTING')
    expect(catalog(env.ctx, fan.uuid).find((c) => c.id === added.id)).toMatchObject({ owned: true, shareable: false })

    // Quelle weg → Annehmen geht nicht.
    const again = createRequest(env.ctx, owner, 'craftinghosting', { kind: 'cape', capeId: mine.id }).request
    run(env.ctx.db, 'UPDATE capes SET retired = 1 WHERE id = ?', mine.id)
    expect(err(() => adminDecideRequest(env.ctx, OWNER, again.id, { status: 'accepted' })).code).toBe('cape_unavailable')
    // Ohne Recht `cosmetics` keine Liste.
    updateMember(env.ctx, owner, 'craftinghosting', helper.uuid, { rights: ['stats'] })
    expect(err(() => selectableCapes(env.ctx, helper.uuid, 'craftinghosting')).code).toBe('partner_right')
  })
})

describe('team cape upload (round 2)', () => {
  it('stores a hidden HD partner cape, assigns it and gives it to current supporters at once', async () => {
    const env = makeEnv()
    const { owner } = await partner(env)
    const fan = await player(env, 'Fan')
    const other = await player(env, 'Other')
    redeemAnyCode(env.ctx, fan.uuid, 'CRAFTING')
    const png = solidPng(1024, 512 * 2, [20, 40, 120, 255])
    expect(err(() => adminUploadPartnerCape(env.ctx, OWNER, 'craftinghosting', png, { name: 'Polarnacht' })).code).toBe('frame_time_required')
    expect(err(() => adminUploadPartnerCape(env.ctx, OWNER, 'craftinghosting', png, { name: 'Polarnacht', frames: '3', frameTimeMs: '150' })).code).toBe('invalid_dimensions')
    expect(err(() => adminUploadPartnerCape(env.ctx, OWNER, 'craftinghosting', solidPng(100, 50), { name: 'X' })).code).toBe('invalid_dimensions')
    const none: Staff = { ...OWNER, owner: false, perms: new Set() }
    expect(err(() => adminUploadPartnerCape(env.ctx, none, 'craftinghosting', png, { name: 'X', frameTimeMs: '150' })).status).toBe(403)

    const r = adminUploadPartnerCape(env.ctx, OWNER, 'craftinghosting', png, { name: 'Polarnacht', frames: '2', frameTimeMs: '150' })
    expect(r.cape).toMatchObject({ name: 'Polarnacht', width: 1024, height: 512, frames: 2, frameTimeMs: 150, granted: 1 })
    expect(getCape(env.ctx, r.cape.id)).toMatchObject({ hidden: 1, partner_id: expect.any(Number), owner_uuid: null, status: 'approved' })
    expect(r.partner.items.map((i) => i.id)).toContain(r.cape.id)
    // Bestehender Unterstützer hat ihn sofort, andere sehen ihn nicht im Katalog.
    expect(catalog(env.ctx, fan.uuid).find((c) => c.id === r.cape.id)).toMatchObject({ owned: true, shareable: false, animated: true })
    expect(catalog(env.ctx, other.uuid).some((c) => c.id === r.cape.id)).toBe(false)
    expect(myPartnerState(env.ctx, fan.uuid).owned.map((o) => o.id)).toContain(r.cape.id)
    expect(partnerDashboard(env.ctx, owner, 'craftinghosting').cosmetics.find((i) => i.id === r.cape.id)!.cape).toMatchObject({ frames: 2, frameTimeMs: 150 })
    // Neue Unterstützer bekommen ihn beim Einlösen.
    redeemAnyCode(env.ctx, other.uuid, 'CRAFTING')
    expect(catalog(env.ctx, other.uuid).find((c) => c.id === r.cape.id)?.owned).toBe(true)
    expect(one(env.ctx.db, "SELECT action FROM admin_log WHERE action = 'partner.cape.upload'")).toEqual({ action: 'partner.cape.upload' })
  })

  it('assigning a catalogue item also gives it to current supporters', async () => {
    const env = makeEnv()
    await partner(env)
    const fan = await player(env, 'Fan')
    redeemAnyCode(env.ctx, fan.uuid, 'CRAFTING')
    const before = catalog(env.ctx, fan.uuid).filter((c) => c.owned).map((c) => c.id)
    const extra = adminCatalog(env.ctx, OWNER).items.find((i) => i.kind === 'cape' && !before.includes(i.id))!
    adminAddItem(env.ctx, OWNER, 'craftinghosting', { kind: 'cape', id: extra.id })
    expect(catalog(env.ctx, fan.uuid).find((c) => c.id === extra.id)?.owned).toBe(true)
  })
})

describe('partner statistics (round 2)', () => {
  it('ranks all partners, keeps a daily history with the previous period and snapshots activity', async () => {
    const env = makeEnv()
    const { owner } = await partner(env)
    await partner(env, 'beta', { code: 'BETACODE', prefix: 'BE', items: false })
    const a = await player(env, 'FanA')
    const b = await player(env, 'FanB')
    const c = await player(env, 'FanC')
    redeemAnyCode(env.ctx, a.uuid, 'CRAFTING')
    env.clock.advance(35 * DAY)
    redeemAnyCode(env.ctx, b.uuid, 'CRAFTING')
    redeemAnyCode(env.ctx, c.uuid, 'BETACODE')
    countClick(env.ctx, 'craftinghosting', undefined, { ip: '1.1.1.1', ua: 'x' })
    setActiveCape(env.ctx, b.uuid, 'emerald')
    const today = Math.floor(env.clock.t / DAY)
    run(env.ctx.db, 'INSERT INTO achievement_playtime (uuid, last_day, last_played_day) VALUES (?, ?, ?)', b.uuid, today, today)
    expect(snapshotPartnerActivity(env.ctx)).toBe(2)
    clearPartnerStatsCache(env.ctx)

    const list = adminPartnerStats(env.ctx, OWNER, {})
    expect(list.partners.map((p) => p.slug)).toEqual(['craftinghosting', 'beta'])
    expect(list.totals).toMatchObject({ partners: 2, supporters: 3, supporters30d: 2, redemptions30d: 2, newPlayers30d: 0 })
    expect(list.partners[0]).toMatchObject({
      code: 'CRAFTING',
      serverAllowed: false,
      supporters: { total: 2, today: 1, d7: 1, d30: 1 },
      redemptions: { total: 2, fixed: 2, onetime: 0, d30: 1 },
      newPlayers: { total: 1, d30: 0 },
      links: { clicks30d: 1, downloads30d: 0 },
      activity: { onlineToday: 1, playedToday: 1, online7d: 1 },
      worn: { cape: 1, cosmetic: 0 },
    })
    expect(list.definitions.activitySince).toBe(new Date(today * DAY).toISOString().slice(0, 10))
    expect(adminPartnerStats(env.ctx, OWNER, { sort: 'name', order: 'asc' }).partners.map((p) => p.slug)).toEqual(['beta', 'craftinghosting'])
    expect(adminPartnerStats(env.ctx, OWNER, { q: 'betac' }).partners.map((p) => p.slug)).toEqual(['beta'])
    expect(err(() => adminPartnerStats(env.ctx, OWNER, { sort: 'nope' })).code).toBe('invalid_request')

    const d = adminPartnerStatsDetail(env.ctx, OWNER, 'craftinghosting', { days: '30' })
    expect(d.series).toHaveLength(30)
    expect(d.series.at(-1)).toMatchObject({ newSupporters: 1, fixed: 1, clicks: 1, activeSupporters: 1, playingSupporters: 1 })
    expect(d.series.at(-2)!.activeSupporters).toBeNull()
    expect(d.period).toMatchObject({ newSupporters: 1, redemptions: 1, newPlayers: 0, clicks: 1 })
    expect(d.previous).toMatchObject({ newSupporters: 1, redemptions: 1, newPlayers: 1 })
    expect(err(() => adminPartnerStatsDetail(env.ctx, OWNER, 'nope', {})).code).toBe('partner_not_found')
    expect(JSON.stringify(d)).not.toContain(b.uuid)

    const stats = partnerDashboard(env.ctx, owner, 'craftinghosting').stats!
    expect(stats.daily).toHaveLength(30)
    expect(stats.current30d).toMatchObject({ newSupporters: 1, redemptions: 1 })
    expect(stats.previous30d).toMatchObject({ newSupporters: 1, newPlayers: 1 })
    expect([stats.clicks30d, stats.left30d]).toEqual([1, 0])

    const none: Staff = { ...OWNER, owner: false, perms: new Set() }
    expect(err(() => adminPartnerStats(env.ctx, none, {})).status).toBe(403)
    expect(err(() => adminPartnerStatsDetail(env.ctx, none, 'beta', {})).status).toBe(403)
  })
})

// ---------------------------------------------------------------- Mehrere feste Codes (Runde 3)

describe('several fixed codes (round 3)', () => {
  const codesOf = (env: TestEnv, owner: string) => partnerDashboard(env.ctx, owner, 'craftinghosting').fixedCodes!

  it('extra codes redeem like the main code and count per code; the popup keeps the main code', async () => {
    const env = makeEnv()
    const { owner } = await partner(env)
    adminAddFixedCode(env.ctx, OWNER, 'craftinghosting', { code: 'ch2026' })
    adminAddFixedCode(env.ctx, OWNER, 'craftinghosting', { code: 'CRAFTINGHOSTING' })
    const card = publicPartner(env.ctx, 'craftinghosting')
    expect(card.code).toBe('CRAFTING')
    expect(card.codes).toEqual(['CRAFTING', 'CH2026', 'CRAFTINGHOSTING'])

    const fan = await player(env, 'ExtraFan')
    const r = redeemAnyCode(env.ctx, fan.uuid, ' ch2026 ')
    expect(r.kind === 'partner' && r.supportChanged).toBe(true)
    expect(r.kind === 'partner' && r.granted.capes.map((c) => c.capeId)).toEqual(['emerald'])
    expect(one(env.ctx.db, 'SELECT partner_id FROM partner_support WHERE uuid = ?', fan.uuid)).toEqual({ partner_id: partnerId(env, 'craftinghosting') })
    // Jeder Code einmal je Spieler; erneut einlösen bleibt idempotent.
    redeemAnyCode(env.ctx, fan.uuid, 'CH2026')
    const again = redeemAnyCode(env.ctx, fan.uuid, 'CRAFTING')
    expect(again.kind === 'partner' && again.supportChanged).toBe(false)
    const other = await player(env, 'OtherFan')
    redeemAnyCode(env.ctx, other.uuid, 'CH2026')

    expect(codesOf(env, owner).map((c) => [c.code, c.main, c.active, c.redemptions, c.redemptions30d])).toEqual([
      ['CRAFTING', true, true, 1, 1],
      ['CH2026', false, true, 2, 2],
      ['CRAFTINGHOSTING', false, true, 0, 0],
    ])
    expect(partnerDashboard(env.ctx, owner, 'craftinghosting').stats!.redemptions).toMatchObject({ total: 3, fixed: 3, onetime: 0 })
    env.clock.advance(31 * DAY)
    expect(codesOf(env, owner)[1]).toMatchObject({ redemptions: 2, redemptions30d: 0 })
    // Ohne Recht `codes` keine Liste.
    const viewer = await player(env, 'StatsOnly')
    inviteMember(env.ctx, owner, 'craftinghosting', { name: 'StatsOnly', rights: ['stats'] })
    answerInvite(env.ctx, viewer.uuid, 'craftinghosting', { accept: true })
    expect(partnerDashboard(env.ctx, viewer.uuid, 'craftinghosting').fixedCodes).toBeNull()
  })

  it('deactivated codes are unknown (404), the main code stays active; only unused extra codes can be removed', async () => {
    const env = makeEnv()
    const { owner } = await partner(env)
    adminAddFixedCode(env.ctx, OWNER, 'craftinghosting', { code: 'CH2026' })
    const fan = await player(env, 'Early')
    redeemAnyCode(env.ctx, fan.uuid, 'CH2026')
    adminUpdateFixedCode(env.ctx, OWNER, 'craftinghosting', 'ch2026', { active: false })
    const late = await player(env, 'Late')
    expect(err(() => redeemAnyCode(env.ctx, late.uuid, 'CH2026')).code).toBe('code_not_found')
    expect(err(() => redeemAnyCode(env.ctx, fan.uuid, 'CH2026')).code).toBe('code_not_found')
    expect(publicPartner(env.ctx, 'craftinghosting').codes).toEqual(['CRAFTING'])
    expect(codesOf(env, owner)[1]).toMatchObject({ code: 'CH2026', active: false, redemptions: 1 })
    // Deaktiviert belegt der Code weiter den Namensraum.
    expect(err(() => adminAddFixedCode(env.ctx, OWNER, 'craftinghosting', { code: 'CH2026' })).code).toBe('code_taken')

    expect(err(() => adminUpdateFixedCode(env.ctx, OWNER, 'craftinghosting', 'CRAFTING', { active: false })).code).toBe('partner_code_main')
    expect(err(() => adminDeleteFixedCode(env.ctx, OWNER, 'craftinghosting', 'CRAFTING')).code).toBe('partner_code_main')
    expect(err(() => adminDeleteFixedCode(env.ctx, OWNER, 'craftinghosting', 'CH2026')).code).toBe('partner_code_redeemed')
    expect(err(() => adminUpdateFixedCode(env.ctx, OWNER, 'craftinghosting', 'NOPE', { active: true })).code).toBe('code_not_found')
    expect(err(() => adminUpdateFixedCode(env.ctx, OWNER, 'craftinghosting', 'CH2026', {})).code).toBe('invalid_request')

    adminUpdateFixedCode(env.ctx, OWNER, 'craftinghosting', 'CH2026', { active: true })
    expect(redeemAnyCode(env.ctx, late.uuid, 'CH2026').kind).toBe('partner')
    adminAddFixedCode(env.ctx, OWNER, 'craftinghosting', { code: 'UNUSED' })
    adminDeleteFixedCode(env.ctx, OWNER, 'craftinghosting', 'UNUSED')
    expect(codesOf(env, owner).map((c) => c.code)).toEqual(['CRAFTING', 'CH2026'])
    expect(all<{ action: string }>(env.ctx.db, "SELECT action FROM admin_log WHERE action LIKE 'partner.code.%' ORDER BY rowid").map((r) => r.action))
      .toEqual(['partner.code.add', 'partner.code.update', 'partner.code.update', 'partner.code.add', 'partner.code.remove'])
  })

  it('switches the main code (own code or rename) and keeps the redemptions', async () => {
    const env = makeEnv()
    const { owner } = await partner(env)
    adminAddFixedCode(env.ctx, OWNER, 'craftinghosting', { code: 'CH2026' })
    adminUpdateFixedCode(env.ctx, OWNER, 'craftinghosting', 'CH2026', { active: false })
    // Zum Haupt-Code machen aktiviert ihn wieder.
    adminUpdateFixedCode(env.ctx, OWNER, 'craftinghosting', 'CH2026', { main: true })
    expect(publicPartner(env.ctx, 'craftinghosting')).toMatchObject({ code: 'CH2026', codes: ['CH2026', 'CRAFTING'] })
    expect(codesOf(env, owner).filter((c) => c.main)).toHaveLength(1)
    const fan = await player(env, 'MainFan')
    redeemAnyCode(env.ctx, fan.uuid, 'CH2026')
    // `code` im Team-PATCH: eigener Code → wird Haupt-Code; neuer Text → Haupt-Code umbenannt.
    adminUpdatePartner(env.ctx, OWNER, 'craftinghosting', { code: 'CRAFTING' })
    expect(publicPartner(env.ctx, 'craftinghosting').code).toBe('CRAFTING')
    adminUpdatePartner(env.ctx, OWNER, 'craftinghosting', { code: 'CRAFTING2' })
    expect(codesOf(env, owner).map((c) => [c.code, c.main])).toEqual([['CRAFTING2', true], ['CH2026', false]])
    adminAddFixedCode(env.ctx, OWNER, 'craftinghosting', { code: 'NEWMAIN', main: true })
    expect(codesOf(env, owner).map((c) => [c.code, c.main, c.redemptions])).toEqual([['NEWMAIN', true, 0], ['CRAFTING2', false, 0], ['CH2026', false, 1]])
    expect(adminListPartners(env.ctx, OWNER).partners[0]).toMatchObject({ code: 'NEWMAIN' })
    expect(adminListPartners(env.ctx, OWNER).partners[0]!.codes).toHaveLength(3)
  })

  it('keeps one namespace with creator codes, other partners and the one-time shape; max 10 per partner', async () => {
    const env = makeEnv()
    const { owner } = await partner(env)
    await partner(env, 'other-host', { code: 'OTHERHOST', prefix: 'OH', items: false })
    await player(env, 'Streamer')
    const c = adminCreateCreator(env.ctx, OWNER, { player: 'Streamer', tier: 'partner' }).creator
    expect(err(() => adminAddFixedCode(env.ctx, OWNER, 'craftinghosting', { code: c.mainCode! })).code).toBe('code_taken')
    expect(err(() => adminAddFixedCode(env.ctx, OWNER, 'craftinghosting', { code: 'OTHERHOST' })).code).toBe('code_taken')
    expect(err(() => adminAddFixedCode(env.ctx, OWNER, 'craftinghosting', { code: 'CRAFTING' })).code).toBe('code_taken')
    expect(err(() => adminAddFixedCode(env.ctx, OWNER, 'craftinghosting', { code: 'CH-2345-6789' })).code).toBe('invalid_request')
    expect(err(() => adminAddFixedCode(env.ctx, OWNER, 'craftinghosting', { code: 'A'.repeat(20) })).code).toBe('invalid_request')
    adminAddFixedCode(env.ctx, OWNER, 'craftinghosting', { code: 'CH2026' })
    expect(err(() => createExtraCode(env.ctx, c.uuid, { code: 'CH2026' })).code).toBe('creator_code_taken')
    expect(err(() => adminUpdatePartner(env.ctx, OWNER, 'other-host', { code: 'CH2026' })).code).toBe('code_taken')
    for (let i = 0; i < 8; i++) adminAddFixedCode(env.ctx, OWNER, 'craftinghosting', { code: `EXTRA${i}` })
    expect(codesOf(env, owner)).toHaveLength(10)
    expect(err(() => adminAddFixedCode(env.ctx, OWNER, 'craftinghosting', { code: 'ELEVENTH' })).code).toBe('partner_code_limit')
    expect(err(() => createRequest(env.ctx, owner, 'craftinghosting', { kind: 'code_change', code: 'ELEVENTH', action: 'add' })).code).toBe('partner_code_limit')
    const none: Staff = { ...OWNER, owner: false, perms: new Set() }
    expect(err(() => adminAddFixedCode(env.ctx, none, 'craftinghosting', { code: 'NOPE1' })).status).toBe(403)
  })

  it('the partner requests an additional code; accepting adds it next to the main code', async () => {
    const env = makeEnv()
    const { owner } = await partner(env)
    expect(err(() => createRequest(env.ctx, owner, 'craftinghosting', { kind: 'code_change', code: 'CRAFTING', action: 'add' })).code).toBe('code_taken')
    expect(err(() => createRequest(env.ctx, owner, 'craftinghosting', { kind: 'code_change', code: 'CH2026', action: 'drop' })).code).toBe('invalid_request')
    const req = createRequest(env.ctx, owner, 'craftinghosting', { kind: 'code_change', code: 'ch2026', action: 'add' }).request
    expect(req.data).toEqual({ code: 'CH2026', action: 'add' })
    expect(publicPartner(env.ctx, 'craftinghosting').codes).toEqual(['CRAFTING'])
    adminDecideRequest(env.ctx, OWNER, req.id, { status: 'accepted' })
    expect(publicPartner(env.ctx, 'craftinghosting')).toMatchObject({ code: 'CRAFTING', codes: ['CRAFTING', 'CH2026'] })
    // Ohne `action` ändert der Antrag weiter den Haupt-Code.
    const change = createRequest(env.ctx, owner, 'craftinghosting', { kind: 'code_change', code: 'CHNEW' }).request
    adminDecideRequest(env.ctx, OWNER, change.id, { status: 'accepted' })
    expect(publicPartner(env.ctx, 'craftinghosting')).toMatchObject({ code: 'CHNEW', codes: ['CHNEW', 'CH2026'] })
  })

  it('migration 53 makes the old fixed code the main code (idempotent)', async () => {
    const env = makeEnv()
    const { owner } = await partner(env)
    await partner(env, 'other-host', { code: 'OTHERHOST', prefix: 'OH', items: false })
    run(env.ctx.db, 'UPDATE partner_codes SET main = 0')
    migratePartners3(env.ctx.db as DatabaseSync)
    migratePartners3(env.ctx.db as DatabaseSync)
    expect(all(env.ctx.db, 'SELECT code FROM partner_codes WHERE main = 1 ORDER BY code')).toEqual([{ code: 'CRAFTING' }, { code: 'OTHERHOST' }])
    expect(one(env.ctx.db, "SELECT 1 AS x FROM sqlite_master WHERE type = 'index' AND name = 'partner_codes_fixed'")).toBeUndefined()
    adminAddFixedCode(env.ctx, OWNER, 'craftinghosting', { code: 'CH2026' })
    // Höchstens ein Haupt-Code je Partner.
    expect(() => run(env.ctx.db, "UPDATE partner_codes SET main = 1 WHERE code = 'CH2026'")).toThrow()
    expect(codesOf(env, owner)).toHaveLength(2)
  })
})

// ---------------------------------------------------------------- Migration

describe('migration 51', () => {
  it('is idempotent and allows the source partner', async () => {
    const env = makeEnv()
    migratePartners(env.ctx.db as DatabaseSync)
    migratePartners(env.ctx.db as DatabaseSync)
    migratePartners2(env.ctx.db as DatabaseSync)
    migratePartners2(env.ctx.db as DatabaseSync)
    migratePartners3(env.ctx.db as DatabaseSync)
    migratePartners3(env.ctx.db as DatabaseSync)
    expect(all(env.ctx.db, "SELECT name FROM pragma_table_info('partner_daily') WHERE name IN ('active_supporters', 'playing_supporters')")).toHaveLength(2)
    seedFixtures(env)
    const fan = await player(env, 'Src')
    run(env.ctx.db, "INSERT INTO user_capes (uuid, cape_id, source, granted_at) VALUES (?, 'emerald', 'partner', 0)", fan.uuid)
    const perms = JSON.parse(one<{ permissions: string }>(env.ctx.db, "SELECT permissions FROM team_roles WHERE id = 'admin'")!.permissions) as string[]
    expect(perms.filter((p) => p === 'partners.manage')).toHaveLength(1)
    expect(one<{ permissions: string }>(env.ctx.db, "SELECT permissions FROM team_roles WHERE id = 'moderator'")!.permissions).not.toContain('partners.manage')
  })

  it('team routes need partners.manage', () => {
    const env = makeEnv()
    const none: Staff = { ...OWNER, owner: false, perms: new Set() }
    expect(err(() => adminListPartners(env.ctx, none)).status).toBe(403)
    expect(err(() => adminCreatePartner(env.ctx, none, {})).status).toBe(403)
  })
})
