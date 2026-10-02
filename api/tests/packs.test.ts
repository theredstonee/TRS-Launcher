import { createReadStream, existsSync, readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { DatabaseSync } from 'node:sqlite'
import { crc32, deflateRawSync } from 'node:zlib'
import { describe, expect, it } from 'vitest'
import { loadConfig } from '../server/lib/config'
import { migrate, one } from '../server/lib/db'
import { block } from '../server/lib/friends'
import { sha256Hex } from '../server/lib/ids'
import { migratePackUploads, migrateSharedPacks } from '../server/lib/migrations'
import { adminReportAction, createReport } from '../server/lib/moderation'
import { modrinthIcon, resetModrinthCache } from '../server/lib/modrinth'
import { inspectPack, listPackContents, packDownloadHost } from '../server/lib/packfile'
import {
  deletePack,
  dismissInbox,
  listOwnPacks,
  lookupPacks,
  normalizePackCode,
  packByCode,
  packContents,
  packInbox,
  planPackDownload,
  readPackFile,
  sendPack,
  setPackDuration,
  sweepExpiredPacks,
  updatePackFile,
  updatePackFromToken,
  uploadPack,
  uploadPackFromToken,
} from '../server/lib/packs'
import {
  completePackUpload,
  createPackUpload,
  packUploadStatus,
  putPackChunk,
  sweepExpiredUploads,
} from '../server/lib/packupload'
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
    // Downloads nur von den erlaubten HTTPS-Hosts, ohne Login, Port oder Lookalike.
    for (const url of [
      'https://evil.example/x.jar',
      'http://cdn.modrinth.com/x.jar',
      'http://edge.forgecdn.net/x.jar',
      'https://cdn.modrinth.com.evil.test/x.jar',
      'https://edge.forgecdn.net.evil.test/x.jar',
      'https://u:p@cdn.modrinth.com/x.jar',
      'https://edge.forgecdn.net:444/x.jar',
      'https://objects.githubusercontent.com/x.jar',
      'https://www.github.com/owner/repo/x.jar',
      'https://github.com.evil.test/x.jar',
    ]) {
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

  it('accepts Modrinth, CurseForge and GitHub links and nothing that only looks like them', () => {
    const ok = (url: string) => {
      const files = [{ path: 'mods/x.jar', hashes: { sha1: SHA1, sha512: SHA512 }, downloads: [url], fileSize: 1 }]
      expect(code(() => inspectPack(zip({ 'modrinth.index.json': index({ files }) })))).toBe('ok')
      expect(packDownloadHost(url)).not.toBeNull()
    }
    ok('https://cdn.modrinth.com/data/AANobbMI/versions/x/sodium.jar')
    ok('https://cdn.modrinth.com:443/data/AANobbMI/versions/x/sodium.jar')
    ok('https://edge.forgecdn.net/files/123/456/sodium.jar')
    ok('https://mediafilez.forgecdn.net/files/123/456/sodium.jar')
    ok('https://github.com/owner/repo/releases/download/v1/sodium.jar')
    ok('https://GitHub.com/owner/repo/releases/download/v1/sodium.jar')
    ok('https://raw.githubusercontent.com/owner/repo/v1/sodium.jar')
    const files = [
      { path: 'mods/sodium.jar', hashes: { sha1: SHA1, sha512: SHA512 }, downloads: ['https://edge.forgecdn.net/files/1/2/sodium.jar'], fileSize: 1 },
      { path: 'mods/extra.jar', hashes: { sha1: SHA1, sha512: SHA512 }, downloads: ['https://github.com/owner/repo/releases/download/v1/extra.jar'], fileSize: 1 },
      { path: 'resourcepacks/pack.zip', hashes: { sha1: SHA1, sha512: SHA512 }, downloads: ['https://raw.githubusercontent.com/owner/repo/main/pack.zip'], fileSize: 1 },
    ]
    const listed = listPackContents(zip({ 'modrinth.index.json': index({ files }) }))
    expect(listed.mods.map((m) => [m.file, m.source, m.projectId])).toEqual([
      ['extra.jar', 'github', null],
      ['sodium.jar', 'curseforge', null],
    ])
    expect(listed.resourcePacks.map((m) => [m.file, m.source])).toEqual([['pack.zip', 'github']])
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
      { name: 'own-mod', file: 'own-mod.jar', source: 'pack', projectId: null, versionId: null },
      { name: 'sodium-fabric-0.6', file: 'sodium-fabric-0.6.jar', source: 'modrinth', projectId: 'AANobbMI', versionId: null },
    ])
    expect(c.resourcePacks).toEqual([
      { name: 'Faithful 32x', file: 'Faithful 32x.zip', source: 'modrinth', projectId: 'FaithFul', versionId: null },
      { name: 'My Pack', file: 'My Pack/', source: 'pack', projectId: null, versionId: null },
    ])
    expect(c.shaderPacks.map((s) => [s.name, s.source, s.projectId])).toEqual([
      ['ComplementaryReimagined', 'modrinth', 'HVnmMxH1'],
      ['Own Shader', 'pack', null],
    ])
  })

  it('reads the stored pack with Modrinth names, versions and icons, without counting an install', async () => {
    resetModrinthCache()
    const env = makeEnv()
    const calls: string[] = []
    env.ctx.modrinthFetch = (async (input: string | URL | Request) => {
      const url = String(input)
      calls.push(url)
      if (url.startsWith('https://api.modrinth.com/v2/projects')) {
        return new Response(JSON.stringify([{ id: 'AANobbMI', slug: 'sodium', title: 'Sodium', project_type: 'mod', icon_url: 'https://cdn.modrinth.com/data/AANobbMI/icon.png' }]))
      }
      if (url.startsWith('https://api.modrinth.com/v2/versions')) {
        return new Response(JSON.stringify([{ id: 'VVVVVVV1', version_number: 'mc1.21.1-0.6.13' }]))
      }
      if (url === 'https://cdn.modrinth.com/data/AANobbMI/icon.png') return new Response(Buffer.from('89504e470d0a1a0a0000', 'hex'))
      return new Response('nope', { status: 404 })
    }) as typeof fetch
    const [alex] = await players(env, 'Alex')
    const files = [{ path: 'mods/sodium.jar', hashes: { sha1: SHA1, sha512: SHA512 }, downloads: ['https://cdn.modrinth.com/data/AANobbMI/versions/VVVVVVV1/sodium.jar'], fileSize: 1 }]
    const shared = uploadPack(env.ctx, alex!.uuid, pack({ files }), '7d')
    const row = packByCode(env.ctx, shared.code)!
    const c = await packContents(env.ctx, row)
    expect(c.mods).toEqual([
      { name: 'own-mod', file: 'own-mod.jar', source: 'pack', projectId: null, versionId: null, title: null, version: null, icon: null, url: null },
      { name: 'sodium', file: 'sodium.jar', source: 'modrinth', projectId: 'AANobbMI', versionId: 'VVVVVVV1', title: 'Sodium', version: 'mc1.21.1-0.6.13', icon: '/v1/modrinth/icon/AANobbMI', url: 'https://modrinth.com/mod/sodium' },
    ])
    // Zweiter Aufruf: alles aus dem Zwischenspeicher.
    const before = calls.length
    await packContents(env.ctx, row)
    expect(calls.length).toBe(before)
    expect(packByCode(env.ctx, shared.code)!.installs).toBe(0)
    const icon = await modrinthIcon(env.ctx, 'AANobbMI')
    expect(icon?.type).toBe('image/png')
    // Unbekanntes Projekt: kein offener Proxy.
    expect(await modrinthIcon(env.ctx, 'ZZZZZZZZ')).toBeNull()
  })

  it('falls back to file names when Modrinth is down', async () => {
    resetModrinthCache()
    const env = makeEnv()
    env.ctx.modrinthFetch = (async () => new Response('down', { status: 503 })) as typeof fetch
    const [alex] = await players(env, 'Alex')
    const shared = uploadPack(env.ctx, alex!.uuid, pack(), '7d')
    const c = await packContents(env.ctx, packByCode(env.ctx, shared.code)!)
    expect(c.mods.map((m) => [m.name, m.title])).toEqual([['own-mod', null], ['sodium', null]])
    resetModrinthCache()
  })
})

function readStream(path: string, start: number, end: number): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = []
    createReadStream(path, { start, end })
      .on('data', (c: Buffer) => chunks.push(c))
      .on('error', reject)
      .on('end', () => resolve(Buffer.concat(chunks)))
  })
}

describe('chunked modpack upload (§27.7)', () => {
  const secret = 'test-secret-key-0123456789abcdef-0123456789'

  function pieces(buf: Buffer, size: number): Buffer[] {
    const out: Buffer[] = []
    for (let i = 0; i < buf.length; i += size) out.push(Buffer.from(buf.subarray(i, Math.min(i + size, buf.length))))
    return out
  }

  it('defaults to 1 GB per pack and 51200 MB of storage, overridable for tests', async () => {
    const defaults = loadConfig({ SECRET_KEY: secret })
    expect(defaults.packMaxBytes).toBe(1024 * 1024 * 1024)
    expect(defaults.packStorageMaxBytes).toBe(51200 * 1024 * 1024)
    expect(defaults.packChunkBytes).toBe(33_554_432)
    const env = makeEnv({ env: { PACK_MAX_MB: '1', PACK_CHUNK_BYTES: '8' } })
    expect(env.ctx.config.packMaxBytes).toBe(1024 * 1024)
    const [alex] = await players(env, 'Alex')
    expect(code(() => createPackUpload(env.ctx, alex!.uuid, { size: 2 * 1024 * 1024, sha256: 'ab'.repeat(32) }))).toBe('payload_too_large')
    const body = pack()
    const up = createPackUpload(env.ctx, alex!.uuid, { size: body.length, sha256: sha256Hex(body) })
    expect(up.chunkSize).toBe(8)
    expect(up.expiresAt).toBe(new Date(env.clock.t + DAY).toISOString())
  })

  it('accepts chunks in any order, a duplicate chunk and a resume, then installs the same bytes', async () => {
    const env = makeEnv({ env: { PACK_CHUNK_BYTES: '8' } })
    const [alex, ben] = await players(env, 'Alex', 'Ben')
    const body = pack({ versionId: '2.0.0' })
    const up = createPackUpload(env.ctx, alex!.uuid, { size: body.length, sha256: sha256Hex(body).toUpperCase(), name: 'Redstone Pack' })
    const piece = pieces(body, up.chunkSize)
    expect(piece.length).toBeGreaterThan(2)
    expect(code(() => packUploadStatus(env.ctx, ben!.uuid, up.uploadId))).toBe('upload_not_found')
    for (const i of piece.map((_, n) => n).reverse()) {
      if (i % 2 === 0) continue
      putPackChunk(env.ctx, alex!.uuid, up.uploadId, i, piece[i]!, sha256Hex(piece[i]!))
    }
    expect(packUploadStatus(env.ctx, alex!.uuid, up.uploadId).received).toEqual(
      piece.map((_, n) => n).filter((n) => n % 2 === 1).sort((a, b) => a - b),
    )
    expect(code(() => completePackUpload(env.ctx, alex!.uuid, up.uploadId))).toBe('upload_incomplete')
    for (const i of piece.map((_, n) => n)) {
      if (i % 2 === 1) continue
      putPackChunk(env.ctx, alex!.uuid, up.uploadId, i, piece[i]!, sha256Hex(piece[i]!))
    }
    putPackChunk(env.ctx, alex!.uuid, up.uploadId, 0, piece[0]!, sha256Hex(piece[0]!))
    expect(packUploadStatus(env.ctx, alex!.uuid, up.uploadId)).toMatchObject({
      received: piece.map((_, n) => n),
      size: body.length,
      chunkSize: 8,
    })
    const done = completePackUpload(env.ctx, alex!.uuid, up.uploadId)
    expect(done.uploadToken).toMatch(/^up_[A-Za-z0-9_-]{43}$/)
    expect(code(() => completePackUpload(env.ctx, alex!.uuid, up.uploadId))).toBe('upload_closed')
    const shared = uploadPackFromToken(env.ctx, alex!.uuid, done.uploadToken, '7d')
    const row = packByCode(env.ctx, shared.code)!
    expect(readPackFile(env.ctx, row, false).equals(body)).toBe(true)
    expect(shared).toMatchObject({ packVersion: '2.0.0', revision: 1 })
    expect(code(() => uploadPackFromToken(env.ctx, alex!.uuid, done.uploadToken, '7d'))).toBe('upload_not_found')
    expect(code(() => updatePackFromToken(env.ctx, ben!.uuid, shared.id, 'up_' + 'a'.repeat(43)))).toBe('pack_not_found')

    const body2 = pack({ versionId: '2.1.0' })
    const up2 = createPackUpload(env.ctx, alex!.uuid, { size: body2.length, sha256: sha256Hex(body2) })
    for (const [i, part] of pieces(body2, up2.chunkSize).entries()) putPackChunk(env.ctx, alex!.uuid, up2.uploadId, i, part, sha256Hex(part))
    const token2 = completePackUpload(env.ctx, alex!.uuid, up2.uploadId).uploadToken
    const updated = updatePackFromToken(env.ctx, alex!.uuid, shared.id, token2)
    expect(updated).toMatchObject({ code: shared.code, revision: 2, packVersion: '2.1.0' })
    expect(readPackFile(env.ctx, packByCode(env.ctx, shared.code)!, false).equals(body2)).toBe(true)
    expect(code(() => updatePackFromToken(env.ctx, alex!.uuid, shared.id, token2))).toBe('upload_not_found')
  })

  it('rejects a bad chunk hash, the wrong size, a conflicting resend and a checksum that does not match', async () => {
    const env = makeEnv({ env: { PACK_CHUNK_BYTES: '8' } })
    const [alex] = await players(env, 'Alex')
    const body = pack()
    const up = createPackUpload(env.ctx, alex!.uuid, { size: body.length, sha256: sha256Hex(body) })
    const piece = pieces(body, up.chunkSize)
    expect(code(() => putPackChunk(env.ctx, alex!.uuid, up.uploadId, 0, piece[0]!, 'b'.repeat(64)))).toBe('checksum_mismatch')
    expect(packUploadStatus(env.ctx, alex!.uuid, up.uploadId).received).toEqual([])
    expect(code(() => putPackChunk(env.ctx, alex!.uuid, up.uploadId, 0, Buffer.alloc(3), sha256Hex(Buffer.alloc(3))))).toBe('invalid_chunk')
    expect(code(() => putPackChunk(env.ctx, alex!.uuid, up.uploadId, 0, Buffer.alloc(9), 'a'.repeat(64)))).toBe('payload_too_large')
    expect(code(() => putPackChunk(env.ctx, alex!.uuid, up.uploadId, piece.length, piece[0]!, sha256Hex(piece[0]!)))).toBe('invalid_chunk')
    putPackChunk(env.ctx, alex!.uuid, up.uploadId, 0, piece[0]!, sha256Hex(piece[0]!))
    const other = Buffer.alloc(piece[0]!.length, 7)
    expect(code(() => putPackChunk(env.ctx, alex!.uuid, up.uploadId, 0, other, sha256Hex(other)))).toBe('chunk_conflict')
    putPackChunk(env.ctx, alex!.uuid, up.uploadId, 0, piece[0]!, sha256Hex(piece[0]!))

    const bad = createPackUpload(env.ctx, alex!.uuid, { size: body.length, sha256: 'a'.repeat(64) })
    putPackChunk(env.ctx, alex!.uuid, bad.uploadId, 0, piece[0]!, sha256Hex(piece[0]!))
    for (let i = 1; i < piece.length; i++) putPackChunk(env.ctx, alex!.uuid, bad.uploadId, i, piece[i]!, sha256Hex(piece[i]!))
    expect(code(() => completePackUpload(env.ctx, alex!.uuid, bad.uploadId))).toBe('checksum_mismatch')
    expect(packUploadStatus(env.ctx, alex!.uuid, bad.uploadId).received).toHaveLength(piece.length)
  })

  it('drops an invalid pack so another upload can start, and expires after 24 hours', async () => {
    const env = makeEnv({ env: { PACK_CHUNK_BYTES: '8' } })
    const [alex] = await players(env, 'Alex')
    const junk = zip({ 'overrides/a.txt': 'no index' })
    const bad = createPackUpload(env.ctx, alex!.uuid, { size: junk.length, sha256: sha256Hex(junk) })
    for (const [i, part] of pieces(junk, bad.chunkSize).entries()) putPackChunk(env.ctx, alex!.uuid, bad.uploadId, i, part, sha256Hex(part))
    expect(code(() => completePackUpload(env.ctx, alex!.uuid, bad.uploadId))).toBe('invalid_pack')
    expect(code(() => packUploadStatus(env.ctx, alex!.uuid, bad.uploadId))).toBe('upload_not_found')

    const body = pack()
    const up = createPackUpload(env.ctx, alex!.uuid, { size: body.length, sha256: sha256Hex(body) })
    putPackChunk(env.ctx, alex!.uuid, up.uploadId, 0, pieces(body, up.chunkSize)[0]!, sha256Hex(pieces(body, up.chunkSize)[0]!))
    expect(existsSync(join(env.ctx.packDir, 'tmp', up.uploadId))).toBe(true)
    env.clock.advance(DAY)
    expect(code(() => packUploadStatus(env.ctx, alex!.uuid, up.uploadId))).toBe('upload_expired')
    expect(code(() => putPackChunk(env.ctx, alex!.uuid, up.uploadId, 1, Buffer.alloc(8), 'a'.repeat(64)))).toBe('upload_expired')
    expect(code(() => completePackUpload(env.ctx, alex!.uuid, up.uploadId))).toBe('upload_expired')
    expect(sweepExpiredUploads(env.ctx)).toBeGreaterThan(0)
    expect(existsSync(join(env.ctx.packDir, 'tmp', up.uploadId))).toBe(false)
    expect(code(() => createPackUpload(env.ctx, alex!.uuid, { size: body.length, sha256: sha256Hex(body) }))).toBe('ok')
  })

  it('allows three open uploads and refuses a fourth; deleting the account removes the chunks', async () => {
    const env = makeEnv({ env: { PACK_CHUNK_BYTES: '8' } })
    const [alex] = await players(env, 'Alex')
    const body = pack()
    const sha = sha256Hex(body)
    const ids = [0, 1, 2].map(() => createPackUpload(env.ctx, alex!.uuid, { size: body.length, sha256: sha }).uploadId)
    expect(code(() => createPackUpload(env.ctx, alex!.uuid, { size: body.length, sha256: sha }))).toBe('upload_limit')
    putPackChunk(env.ctx, alex!.uuid, ids[0]!, 0, pieces(body, 8)[0]!, sha256Hex(pieces(body, 8)[0]!))
    deleteUser(env.ctx, alex!.uuid)
    expect(existsSync(join(env.ctx.packDir, 'tmp', ids[0]!))).toBe(false)
  })

  it('streams a byte range with the right length and counts an install only from the start', async () => {
    const env = makeEnv()
    const [alex, ben] = await players(env, 'Alex', 'Ben')
    const body = pack()
    const shared = uploadPack(env.ctx, alex!.uuid, body, '7d')
    const row = packByCode(env.ctx, shared.code)!
    const plan = planPackDownload(env.ctx, row, 'bytes=0-4', ben!.uuid)
    expect(plan).toMatchObject({ status: 206, contentLength: 5, contentRange: `bytes 0-4/${body.length}`, countInstall: true })
    expect((await readStream(plan.path, plan.start, plan.end)).equals(body.subarray(0, 5))).toBe(true)
    const middle = planPackDownload(env.ctx, row, 'bytes=1-4', ben!.uuid)
    expect(middle).toMatchObject({ status: 206, contentLength: 4, contentRange: `bytes 1-4/${body.length}`, countInstall: false })
    expect((await readStream(middle.path, middle.start, middle.end)).equals(body.subarray(1, 5))).toBe(true)
    const tail = planPackDownload(env.ctx, row, `bytes=${body.length - 3}-`, ben!.uuid)
    expect(tail.countInstall).toBe(false)
    expect(tail.start).toBe(body.length - 3)
    expect(tail.contentLength).toBe(3)
    expect((await readStream(tail.path, tail.start, tail.end)).equals(body.subarray(body.length - 3))).toBe(true)
    expect(planPackDownload(env.ctx, row, 'bytes=0-1,2-3', ben!.uuid).status).toBe(200)
    expect(planPackDownload(env.ctx, row, undefined, alex!.uuid)).toMatchObject({ status: 200, contentLength: body.length, countInstall: false })
    expect(code(() => planPackDownload(env.ctx, row, 'bytes=999999-1000000', ben!.uuid))).toBe('range_not_satisfiable')
    expect(code(() => planPackDownload(env.ctx, row, 'bytes=-0', ben!.uuid))).toBe('range_not_satisfiable')
  })

  it('migration 22 is idempotent', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('PRAGMA foreign_keys = ON')
    migrate(db)
    migratePackUploads(db)
    migratePackUploads(db)
    expect(one(db, "SELECT 1 AS x FROM sqlite_master WHERE name = 'pack_upload_sessions'")).toBeDefined()
    expect(one(db, "SELECT 1 AS x FROM sqlite_master WHERE name = 'pack_upload_chunks'")).toBeDefined()
  })
})
