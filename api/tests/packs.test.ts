import { existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { DatabaseSync } from 'node:sqlite'
import { crc32, deflateRawSync } from 'node:zlib'
import { describe, expect, it } from 'vitest'
import { migrate, one } from '../server/lib/db'
import { block } from '../server/lib/friends'
import { migrateSharedPacks } from '../server/lib/migrations'
import { adminReportAction, createReport } from '../server/lib/moderation'
import { inspectPack, listPackContents } from '../server/lib/packfile'
import {
  deletePack,
  dismissInbox,
  listOwnPacks,
  lookupPacks,
  normalizePackCode,
  packByCode,
  packContents,
  packInbox,
  readPackFile,
  sendPack,
  setPackDuration,
  sweepExpiredPacks,
  updatePackFile,
  uploadPack,
} from '../server/lib/packs'
import { createSanction } from '../server/lib/sanctions'
import { ownerStaff } from '../server/lib/team'
import { deleteUser } from '../server/lib/users'
import { befriend, code, listen, players } from './chathelpers'
import { ADMIN, makeEnv, type TestEnv } from './helpers'

const DAY = 24 * 60 * 60 * 1000
const SHA1 = 'a'.repeat(40)
const SHA512 = 'b'.repeat(128)

/** Minimales Zip (Deflate), wie es der Launcher-Export schreibt. */
function zip(files: Record<string, string | Buffer>): Buffer {
  const locals: Buffer[] = []
  const central: Buffer[] = []
  let offset = 0
  for (const [name, content] of Object.entries(files)) {
    const data = Buffer.isBuffer(content) ? content : Buffer.from(content)
    const comp = deflateRawSync(data)
    const n = Buffer.from(name)
    const crc = crc32(data) >>> 0
    const lh = Buffer.alloc(30)
    lh.writeUInt32LE(0x04034b50, 0)
    lh.writeUInt16LE(20, 4)
    lh.writeUInt16LE(8, 8)
    lh.writeUInt32LE(crc, 14)
    lh.writeUInt32LE(comp.length, 18)
    lh.writeUInt32LE(data.length, 22)
    lh.writeUInt16LE(n.length, 26)
    locals.push(lh, n, comp)
    const ch = Buffer.alloc(46)
    ch.writeUInt32LE(0x02014b50, 0)
    ch.writeUInt16LE(20, 4)
    ch.writeUInt16LE(20, 6)
    ch.writeUInt16LE(8, 10)
    ch.writeUInt32LE(crc, 16)
    ch.writeUInt32LE(comp.length, 20)
    ch.writeUInt32LE(data.length, 24)
    ch.writeUInt16LE(n.length, 28)
    ch.writeUInt32LE(offset, 42)
    central.push(ch, n)
    offset += 30 + n.length + comp.length
  }
  const cd = Buffer.concat(central)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(Object.keys(files).length, 8)
  end.writeUInt16LE(Object.keys(files).length, 10)
  end.writeUInt32LE(cd.length, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...locals, cd, end])
}

function index(over: Record<string, unknown> = {}): string {
  return JSON.stringify({
    formatVersion: 1,
    game: 'minecraft',
    versionId: '1.0.0',
    name: 'Redstone Pack',
    summary: 'Tech & Redstone',
    files: [
      { path: 'mods/sodium.jar', hashes: { sha1: SHA1, sha512: SHA512 }, downloads: ['https://cdn.modrinth.com/data/AANobbMI/versions/x/sodium.jar'], fileSize: 1234 },
    ],
    dependencies: { 'minecraft': '1.21.1', 'fabric-loader': '0.16.9' },
    ...over,
  })
}

function pack(over: Record<string, unknown> = {}, extra: Record<string, string> = {}): Buffer {
  return zip({
    'modrinth.index.json': index(over),
    'overrides/config/sodium.json': '{"a":1}',
    'overrides/mods/own-mod.jar': 'PK-jar',
    'overrides/options.txt': 'fov:90',
    ...extra,
  })
}

function packFiles(env: TestEnv): string[] {
  if (!existsSync(env.ctx.packDir)) return []
  return readdirSync(env.ctx.packDir).flatMap((d) => readdirSync(join(env.ctx.packDir, d)))
}

describe('pack file check (§27)', () => {
  it('describes a valid .mrpack', () => {
    const info = inspectPack(pack())
    expect(info).toMatchObject({
      name: 'Redstone Pack',
      summary: 'Tech & Redstone',
      packVersion: '1.0.0',
      mcVersion: '1.21.1',
      loader: 'fabric',
      loaderVersion: '0.16.9',
      downloads: 1,
      ownJars: 1,
      overrides: 2,
    })
    expect(inspectPack(zip({ 'modrinth.index.json': index({ dependencies: { minecraft: '1.20.1' } }) })).loader).toBe('vanilla')
  })

  it('rejects everything that is not a clean Modrinth pack', () => {
    const bad = (buf: Buffer) => code(() => inspectPack(buf))
    expect(bad(Buffer.from('not a zip at all, sorry'))).toBe('invalid_pack')
    expect(bad(zip({ 'overrides/a.txt': 'x' }))).toBe('invalid_pack')
    expect(bad(zip({ 'modrinth.index.json': '{no json' }))).toBe('invalid_pack')
    // Downloads nur von Modrinths CDN.
    for (const url of ['https://evil.example/x.jar', 'http://cdn.modrinth.com/x.jar', 'https://cdn.modrinth.com.evil.test/x.jar', 'https://u:p@cdn.modrinth.com/x.jar']) {
      const files = [{ path: 'mods/x.jar', hashes: { sha1: SHA1, sha512: SHA512 }, downloads: [url], fileSize: 1 }]
      expect(bad(zip({ 'modrinth.index.json': index({ files }) }))).toBe('invalid_pack')
    }
    // Pfade, die aus dem Spielordner ausbrechen.
    for (const path of ['../evil.jar', 'mods/../../evil.jar', '/etc/passwd', 'C:/x.jar', 'mods\\x.jar']) {
      const files = [{ path, hashes: { sha1: SHA1, sha512: SHA512 }, downloads: ['https://cdn.modrinth.com/x.jar'], fileSize: 1 }]
      expect(bad(zip({ 'modrinth.index.json': index({ files }) }))).toBe('invalid_pack')
    }
    expect(bad(pack({}, { 'overrides/../../evil.bat': 'x' }))).toBe('invalid_pack')
    expect(bad(pack({}, { 'evil.exe': 'x' }))).toBe('invalid_pack')
    expect(bad(zip({ 'modrinth.index.json': index({ dependencies: { minecraft: '1.21.1', 'fabric-loader': '1', 'forge': '2' } }) }))).toBe('invalid_pack')
    expect(bad(zip({ 'modrinth.index.json': index({ dependencies: { minecraft: '1.21.1', 'evil-loader': '1' } }) }))).toBe('invalid_pack')
    expect(bad(zip({ 'modrinth.index.json': index({ dependencies: { 'fabric-loader': '1' } }) }))).toBe('invalid_pack')
    expect(bad(zip({ 'modrinth.index.json': index({ game: 'terraria' }) }))).toBe('invalid_pack')
  })

  it('normalizes codes forgivingly', () => {
    expect(normalizePackCode('TRS-AB12-CD34')).toBe('AB12CD34')
    expect(normalizePackCode('trs ab12 cd34')).toBe('AB12CD34')
    expect(normalizePackCode('ab12cd34')).toBe('AB12CD34')
    expect(normalizePackCode('Ab1O-CDl4')).toBe('AB10CD14')
    expect(normalizePackCode('AB12CD3')).toBeNull()
    expect(normalizePackCode('AB12CD3U')).toBeNull()
    expect(normalizePackCode("'; DROP TABLE x")).toBeNull()
  })
})

describe('shared modpacks (§27)', () => {
  it('shares, finds by code, downloads and updates with a new revision', async () => {
    const env = makeEnv()
    const [alex, ben] = await players(env, 'Alex', 'Ben')
    const shared = uploadPack(env.ctx, alex.uuid, pack(), '7d')
    expect(shared.code).toMatch(/^TRS-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/)
    expect(shared.url).toContain(`/p/${shared.code}`)
    expect(shared).toMatchObject({ revision: 1, name: 'Redstone Pack', mcVersion: '1.21.1', ownJars: 1, owner: { uuid: alex.uuid, name: 'Alex' } })
    expect(shared.expiresAt).toBe(new Date(env.clock.t + 7 * DAY).toISOString())

    const found = packByCode(env.ctx, shared.code.toLowerCase().replace(/-/g, ''))!
    expect(found.id).toBe(shared.id)
    expect(readPackFile(env.ctx, found, true).equals(pack())).toBe(true)
    expect(listOwnPacks(env.ctx, alex.uuid).packs[0]!.installs).toBe(1)

    befriend(env, alex, ben)
    sendPack(env.ctx, alex.uuid, shared.id, [ben.uuid])
    const benEvents = listen(env, ben.uuid)
    const v2 = updatePackFile(env.ctx, alex.uuid, shared.id, pack({ versionId: '1.1.0' }))
    expect(v2).toMatchObject({ code: shared.code, revision: 2, packVersion: '1.1.0' })
    expect(benEvents.of('pack_updated')[0]!.pack.revision).toBe(2)
    // Nur die aktuelle Version liegt auf der Platte.
    expect(packFiles(env)).toEqual([`${shared.id}.2.mrpack`])
    expect(code(() => updatePackFile(env.ctx, alex.uuid, shared.id, pack({ versionId: '1.1.0' })))).toBe('pack_unchanged')
    // Nur der Besitzer darf neue Versionen hochladen.
    expect(code(() => updatePackFile(env.ctx, ben.uuid, shared.id, pack({ versionId: '9' })))).toBe('pack_not_found')
    expect(lookupPacks(env.ctx, [shared.code, 'TRS-0000-0000', 'garbage']).map((p) => p.revision)).toEqual([2])
  })

  it('expires by the chosen duration; forever stays; duration can be changed', async () => {
    const env = makeEnv()
    const [alex] = await players(env, 'Alex')
    const day = uploadPack(env.ctx, alex.uuid, pack(), '1d')
    const forever = uploadPack(env.ctx, alex.uuid, pack({ name: 'Forever' }), 'forever')
    expect(forever.expiresAt).toBeNull()
    env.clock.advance(DAY + 1)
    expect(packByCode(env.ctx, day.code)).toBeUndefined()
    expect(packByCode(env.ctx, forever.code)).toBeDefined()
    expect(sweepExpiredPacks(env.ctx)).toBe(1)
    expect(packFiles(env)).toEqual([`${forever.id}.1.mrpack`])
    const changed = setPackDuration(env.ctx, alex.uuid, forever.id, '30d')
    expect(changed.expiresAt).toBe(new Date(env.clock.t + 30 * DAY).toISOString())
  })

  it('enforces limits and the upload ban', async () => {
    const env = makeEnv({ limits: { maxSharedPacks: 2, maxPackUploadsPerDay: 3 } })
    const [alex, bea] = await players(env, 'Alex', 'Bea')
    const a = uploadPack(env.ctx, alex.uuid, pack(), '1d')
    uploadPack(env.ctx, alex.uuid, pack({ name: 'Two' }), '1d')
    expect(code(() => uploadPack(env.ctx, alex.uuid, pack({ name: 'Three' }), '1d'))).toBe('pack_limit')
    updatePackFile(env.ctx, alex.uuid, a.id, pack({ versionId: '2' }))
    // Drei Uploads heute (auch das Update zählt), Löschen setzt die Grenze nicht zurück.
    deletePack(env.ctx, alex.uuid, a.id)
    expect(code(() => uploadPack(env.ctx, alex.uuid, pack({ name: 'Four' }), '1d'))).toBe('pack_daily_limit')
    env.ctx.config.packMaxBytes = 10
    expect(code(() => uploadPack(env.ctx, bea.uuid, pack(), '1d'))).toBe('payload_too_large')
    env.ctx.config.packMaxBytes = 50 * 1024 * 1024
    createSanction(env.ctx, ownerStaff(ADMIN), { uuid: bea.uuid, kind: 'upload_ban', minutes: null, reasonCode: 'other', reason: null, note: null })
    expect(code(() => uploadPack(env.ctx, bea.uuid, pack(), '1d'))).toBe('sanctioned')
  })

  it('sends only to friends, lists and dismisses, removes on delete', async () => {
    const env = makeEnv()
    const [alex, ben, cleo, dora] = await players(env, 'Alex', 'Ben', 'Cleo', 'Dora')
    befriend(env, alex, ben)
    befriend(env, alex, dora)
    const p = uploadPack(env.ctx, alex.uuid, pack(), '7d')
    expect(code(() => sendPack(env.ctx, alex.uuid, p.id, [cleo.uuid]))).toBe('no_recipients')
    block(env.ctx, dora.uuid, { uuid: alex.uuid })
    const benEvents = listen(env, ben.uuid)
    const r = sendPack(env.ctx, alex.uuid, p.id, [ben.uuid, cleo.uuid, dora.uuid])
    expect(r.sent.map((x) => x.name)).toEqual(['Ben'])
    expect(r.skipped.sort()).toEqual([cleo.uuid, dora.uuid].sort())
    expect(benEvents.of('pack_shared')[0]).toMatchObject({ pack: { id: p.id }, from: { name: 'Alex' } })
    expect(packInbox(env.ctx, ben.uuid).map((x) => x.pack.name)).toEqual(['Redstone Pack'])
    // Ben kann an seine eigenen Freunde weiterschicken.
    befriend(env, ben, cleo)
    expect(sendPack(env.ctx, ben.uuid, p.id, [cleo.uuid]).sent[0]!.name).toBe('Cleo')
    expect(packInbox(env.ctx, cleo.uuid)[0]!.from.name).toBe('Ben')
    dismissInbox(env.ctx, ben.uuid, p.id)
    expect(packInbox(env.ctx, ben.uuid)).toEqual([])
    const cleoEvents = listen(env, cleo.uuid)
    deletePack(env.ctx, alex.uuid, p.id)
    expect(cleoEvents.of('pack_removed')[0]!.packId).toBe(p.id)
    expect(packInbox(env.ctx, cleo.uuid)).toEqual([])
    expect(packFiles(env)).toEqual([])
  })

  it('can be reported and deleted by moderation; account deletion removes files', async () => {
    const env = makeEnv()
    const [alex, ben] = await players(env, 'Alex', 'Ben')
    const p = uploadPack(env.ctx, alex.uuid, pack(), 'forever')
    expect(code(() => createReport(env.ctx, alex.uuid, { kind: 'pack', packId: p.id, reason: 'scam_phishing' }))).toBe('cannot_target_self')
    const rep = createReport(env.ctx, ben.uuid, { kind: 'pack', packId: p.id, reason: 'scam_phishing' })
    expect(code(() => createReport(env.ctx, ben.uuid, { kind: 'pack', packId: p.id, reason: 'spam' }))).toBe('already_reported')
    const detail = adminReportAction(env.ctx, ADMIN, rep.id, { action: 'delete_pack' })
    expect(detail.status).toBe('resolved')
    expect(packByCode(env.ctx, p.code)).toBeUndefined()
    expect(packFiles(env)).toEqual([])

    const q = uploadPack(env.ctx, alex.uuid, pack({ name: 'Other' }), '30d')
    expect(packFiles(env)).toEqual([`${q.id}.1.mrpack`])
    deleteUser(env.ctx, alex.uuid)
    expect(packFiles(env)).toEqual([])
    expect(one(env.ctx.db, 'SELECT 1 AS x FROM shared_packs')).toBeUndefined()
  })

  it('hides packs of banned owners', async () => {
    const env = makeEnv()
    const [alex] = await players(env, 'Alex')
    const p = uploadPack(env.ctx, alex.uuid, pack(), '7d')
    createSanction(env.ctx, ownerStaff(ADMIN), { uuid: alex.uuid, kind: 'account_ban', minutes: null, reasonCode: 'other', reason: null, note: null })
    expect(packByCode(env.ctx, p.code)).toBeUndefined()
  })

  it('migration 16 is idempotent', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('PRAGMA foreign_keys = ON')
    migrate(db)
    migrateSharedPacks(db)
    migrateSharedPacks(db)
    expect(one(db, "SELECT 1 AS x FROM sqlite_master WHERE name = 'chat_reports_pack'")).toBeDefined()
  })
})

describe('pack contents for the website (§27.6)', () => {
  it('lists mods, resource packs and shaders from the index and overrides', () => {
    const files = [
      { path: 'mods/sodium-fabric-0.6.jar', hashes: { sha1: SHA1, sha512: SHA512 }, downloads: ['https://cdn.modrinth.com/data/AANobbMI/versions/x/sodium.jar'], fileSize: 1 },
      { path: 'mods/server-only.jar', hashes: { sha1: SHA1, sha512: SHA512 }, env: { client: 'unsupported', server: 'required' }, downloads: ['https://cdn.modrinth.com/data/BBBBBBBB/versions/x/s.jar'], fileSize: 1 },
      { path: 'resourcepacks/Faithful 32x.zip', hashes: { sha1: SHA1, sha512: SHA512 }, downloads: ['https://cdn.modrinth.com/data/FaithFul/versions/y/f.zip'], fileSize: 1 },
      { path: 'shaderpacks/ComplementaryReimagined.zip', hashes: { sha1: SHA1, sha512: SHA512 }, downloads: ['https://cdn.modrinth.com/data/HVnmMxH1/versions/z/c.zip'], fileSize: 1 },
      { path: 'config/x.json', hashes: { sha1: SHA1, sha512: SHA512 }, downloads: ['https://cdn.modrinth.com/data/CCCCCCCC/versions/z/x.json'], fileSize: 1 },
    ]
    const c = listPackContents(pack({ files }, {
      'overrides/resourcepacks/My Pack/pack.mcmeta': '{}',
      'overrides/resourcepacks/My Pack/pack.png': 'x',
      'overrides/shaderpacks/Own Shader.zip': 'x',
      'server-overrides/mods/server.jar': 'x',
    }))
    expect(c.mods).toEqual([
      { name: 'own-mod', file: 'own-mod.jar', source: 'pack', projectId: null },
      { name: 'sodium-fabric-0.6', file: 'sodium-fabric-0.6.jar', source: 'modrinth', projectId: 'AANobbMI' },
    ])
    expect(c.resourcePacks).toEqual([
      { name: 'Faithful 32x', file: 'Faithful 32x.zip', source: 'modrinth', projectId: 'FaithFul' },
      { name: 'My Pack', file: 'My Pack/', source: 'pack', projectId: null },
    ])
    expect(c.shaderPacks.map((s) => [s.name, s.source, s.projectId])).toEqual([
      ['ComplementaryReimagined', 'modrinth', 'HVnmMxH1'],
      ['Own Shader', 'pack', null],
    ])
  })

  it('reads the stored pack without counting an install', async () => {
    const env = makeEnv()
    const [alex] = await players(env, 'Alex')
    const shared = uploadPack(env.ctx, alex!.uuid, pack(), '7d')
    const row = packByCode(env.ctx, shared.code)!
    const c = packContents(env.ctx, row)
    expect(c.mods.map((m) => m.name)).toEqual(['own-mod', 'sodium'])
    expect(packContents(env.ctx, row)).toBe(c)
    expect(packByCode(env.ctx, shared.code)!.installs).toBe(0)
  })
})
