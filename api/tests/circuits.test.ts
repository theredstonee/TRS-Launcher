import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { gzipSync } from 'node:zlib'
import { DatabaseSync } from 'node:sqlite'
import { describe, expect, it } from 'vitest'
import {
  CIRCUIT_BLOCKS,
  canonicalContent,
  checkCircuit,
  circuitOfGrid,
  gridOf,
  type CircuitData,
} from '../shared/circuits'
import { PERMISSIONS } from '../shared/team'
import { exportStructureNbt, importCircuitFile } from '../server/lib/circuit-files'
import {
  acceptSubmission,
  circuitIndex,
  circuitJson,
  createCircuit,
  deleteCircuit,
  getCircuit,
  listAdminCircuits,
  mySubmissions,
  rejectSubmission,
  seedCircuits,
  setCircuitStatus,
  submitCircuit,
  sweepCircuitSubmissions,
  updateCircuit,
} from '../server/lib/circuits'
import { all, migrate, one, run } from '../server/lib/db'
import { MIGRATIONS, migrateCircuits } from '../server/lib/migrations'
import { adminReportAction, createReport } from '../server/lib/moderation'
import { readNbt, TAG } from '../server/lib/nbt'
import { createSanction } from '../server/lib/sanctions'
import { can, ownerStaff, setMemberRoles, teamOf } from '../server/lib/team'
import { deleteUser, getUser } from '../server/lib/users'
import indexRoute from '../server/routes/v1/circuits/index/index.get'
import circuitRoute from '../server/routes/v1/circuits/[id]/index.get'
import exportRoute from '../server/routes/v1/circuits/[id]/export.get'
import submitRoute from '../server/routes/v1/circuits/submissions.post'
import adminListRoute from '../server/routes/v1/admin/circuits/index.get'
import adminImportRoute from '../server/routes/v1/admin/circuits/import.post'
import { code, listen, players } from './chathelpers'
import { bundledCircuit, bundledSeed, callRoute, seedAll, toLitematic, toSponge } from './circuithelpers'
import { ADMIN, login, makeEnv, type TestEnv } from './helpers'

const OWNER = ownerStaff(ADMIN)
const DAY = 86_400_000

function andGate(): CircuitData {
  return bundledCircuit('and_gate')
}

/** Einfache gültige Schaltung (Hebel → Lampe) mit eigener ID. */
function small(id: string, extra = ''): Record<string, unknown> {
  return {
    format: 1,
    id,
    category: 'basics',
    difficulty: 1,
    texts: { en: { name: `Test ${id}`, desc: 'A lever and a lamp.' } },
    palette: { A: 'lever[face=floor,facing=north]@A', '-': 'redstone_wire', L: 'redstone_lamp@Q' },
    layers: [[`A-L${extra}`]],
  }
}

describe('circuit format (shared/circuits.ts)', () => {
  it('accepts all 25 bundled circuits and the catalog matches blocks.json', () => {
    const seed = bundledSeed()
    expect(seed.order).toHaveLength(25)
    for (const id of seed.order) {
      const r = checkCircuit(seed.files.get(id))
      expect(r.ok, `${id}: ${r.ok ? '' : r.errors.join('; ')}`).toBe(true)
    }
    const blocks = (JSON.parse(readFileSync(join(__dirname, '..', 'assets', 'circuits', 'blocks.json'), 'utf8')) as { blocks: object }).blocks
    expect(Object.keys(blocks).sort()).toEqual(CIRCUIT_BLOCKS.map((b) => b.key).sort())
    // Versionen wie im Client (höchste aus Blöcken und Angabe)
    const since = (id: string) => (checkCircuit(seed.files.get(id)) as { info: { since: string } }).info.since
    expect(since('observer_clock')).toBe('1.11')
    expect(since('t_flipflop_copper')).toBe('1.21')
    expect(since('item_elevator')).toBe('1.13')
    expect(since('t_flipflop')).toBe('1.8')
  })

  it('rejects invalid circuits with reasons', () => {
    const errs = (c: unknown) => {
      const r = checkCircuit(c)
      return r.ok ? [] : r.errors.join(' | ')
    }
    expect(errs(small('ok'))).toEqual([])
    expect(errs({ ...small('x'), palette: { A: 'tnt' }, layers: [['A']] })).toContain('unknown block')
    expect(errs({ ...small('x'), palette: { A: 'repeater[delay=9]' }, layers: [['A']] })).toContain('delay=9 is not allowed')
    expect(errs({ ...small('x'), palette: { A: 'lever[powered=true]' }, layers: [['A']] })).toContain('no property "powered"')
    // NBT / Kisteninhalte gibt es im Format nicht – auch nicht als Eigenschaft
    expect(errs({ ...small('x'), palette: { A: 'chest[Items=diamond]' }, layers: [['A']] })).toContain('no property')
    expect(errs({ ...small('x'), nbt: { Items: [] } })).toContain('unknown field "nbt"')
    expect(errs({ ...small('x'), layers: [['A-L'.padEnd(17, '-')]] })).toContain('at most 16')
    expect(errs({ ...small('x'), layers: Array.from({ length: 17 }, () => ['A']) })).toContain('1–16 layers')
    expect(errs({ ...small('x'), layers: [['AZL']] })).toContain('"Z" is not in the palette')
    expect(errs({ ...small('x'), id: 'Bad-ID' })).toContain('id:')
    expect(errs({ ...small('index') })).toContain('reserved')
    expect(errs({ ...small('x'), category: 'bombs' })).toContain('category')
    expect(errs({ ...small('x'), difficulty: 4 })).toContain('difficulty')
    expect(errs({ ...small('x'), since: '1.21-pre1' })).toContain('since')
    expect(errs({ ...small('x'), texts: { en: { name: 'x'.repeat(65) } } })).toContain('longer than 64')
    expect(errs({ ...small('x'), texts: { en: { name: '§cRot' } } })).toContain('formatting codes')
    expect(errs({ ...small('x'), texts: { en: { desc: 'a\u0007b' } } })).toContain('control characters')
    expect(errs({ ...small('x'), texts: { xx_YY: { name: 'a' } } })).toContain('bad language')
    const bigPalette: Record<string, string> = {}
    const chars = [...'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!$%&*+/:;<=']
    chars.forEach((ch, i) => (bigPalette[ch] = `repeater[delay=${(i % 4) + 1},facing=${['north', 'east', 'south', 'west'][Math.floor(i / 4) % 4]}]@M${i}`))
    expect(Object.keys(bigPalette).length).toBeGreaterThan(64)
    expect(errs({ ...small('x'), palette: bigPalette, layers: [['A']] })).toContain('more than 64')
    // Tests: Anschlüsse müssen existieren, Grenzen wie im Client
    expect(errs({ ...small('x'), tests: [{ truth: { in: ['A'], out: ['Z'], rows: ['0:0'] } }] })).toContain('marker "Z"')
    expect(errs({ ...small('x'), tests: [{ steps: [{ run: 5000 }] }] })).toContain('run 0–2000')
    expect(errs({ ...small('x'), tests: [{ hack: 1 }] })).toContain('unknown key')
    expect(errs({ ...small('x'), tests: [{ truth: { in: ['A'], out: ['Q'], rows: ['0:0', '1:1'] } }] })).toEqual([])
    expect(errs('nope')).toContain('JSON object')
  })

  it('grid round trip keeps the build (palette chars may change)', () => {
    for (const id of ['xor_gate', 'piston_door_2x2', 'item_filter']) {
      const c = bundledCircuit(id)
      const back = circuitOfGrid(gridOf(c), c.palette)
      const a = checkCircuit(c)
      const b = checkCircuit({ ...c, ...back })
      expect(a.ok && b.ok).toBe(true)
      if (a.ok && b.ok) expect(canonicalContent(b.info.cells)).toBe(canonicalContent(a.info.cells))
    }
  })
})

describe('migration 14 + seed', () => {
  it('creates the tables, grants circuits.manage to the default roles and is idempotent', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('PRAGMA foreign_keys = ON')
    migrate(db)
    const version = (db.prepare('SELECT MAX(version) AS v FROM schema_migrations').get() as { v: number }).v
    expect(version).toBe(MIGRATIONS.at(-1)!.version)
    expect(version).toBe(14)
    for (const t of ['circuits', 'circuit_tombstones', 'circuit_submissions']) {
      expect(db.prepare("SELECT 1 AS x FROM sqlite_master WHERE type = 'table' AND name = ?").get(t)).toBeTruthy()
    }
    const perms = (id: string) => JSON.parse((db.prepare('SELECT permissions FROM team_roles WHERE id = ?').get(id) as { permissions: string }).permissions) as string[]
    for (const id of ['owner', 'admin', 'senior_moderator', 'content']) expect(perms(id)).toContain('circuits.manage')
    expect(perms('moderator')).not.toContain('circuits.manage')
    expect(perms('supporter')).not.toContain('circuits.manage')
    // Meldungen: neue Art + Spalte, zweiter Lauf ändert nichts
    const cols = db.prepare('PRAGMA table_info(chat_reports)').all() as { name: string }[]
    expect(cols.some((c) => c.name === 'circuit_id')).toBe(true)
    migrateCircuits(db)
    migrateCircuits(db)
    expect(perms('admin').filter((p) => p === 'circuits.manage')).toHaveLength(1)
    expect(PERMISSIONS).toContain('circuits.manage')
  })

  it('keeps existing reports when rebuilding the report table (upgrade from 13)', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('PRAGMA foreign_keys = ON')
    db.exec('CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)')
    // Stand 13 herstellen: alle Migrationen bis 13
    for (const m of MIGRATIONS.filter((x) => x.version <= 13)) {
      if (m.sql) db.exec(m.sql)
      m.run?.(db)
      db.prepare('INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)').run(m.version, 1)
    }
    db.prepare("INSERT INTO users (uuid, name, name_lower, created_at, last_login_at) VALUES (?, 'A', 'a', 1, 1)").run('a'.repeat(32))
    db.prepare(`INSERT INTO chat_reports (id, reporter_uuid, target_uuid, kind, reason, status, created_at, updated_at)
      VALUES ('r0000000000000001', ?, NULL, 'player', 'spam', 'open', 1, 1)`).run('a'.repeat(32))
    db.prepare("INSERT INTO chat_report_notes (report_id, at, actor, text) VALUES ('r0000000000000001', 1, 'x', x'00')").run()
    expect(migrate(db)).toBe(1)
    expect(db.prepare('SELECT COUNT(*) AS n FROM chat_reports').get()).toEqual({ n: 1 })
    expect(db.prepare('SELECT COUNT(*) AS n FROM chat_report_notes').get()).toEqual({ n: 1 })
    db.prepare(`INSERT INTO chat_reports (id, reporter_uuid, target_uuid, kind, circuit_id, reason, status, created_at, updated_at)
      VALUES ('r0000000000000002', NULL, NULL, 'circuit', 'and_gate', 'spam', 'open', 1, 1)`).run()
  })

  it('seeds the 25 circuits once, updates untouched ones, never overwrites edited or deleted ones', async () => {
    const env = makeEnv()
    const first = seedAll(env)
    expect(first).toMatchObject({ inserted: 25, updated: 0, skipped: 0, invalid: [] })
    expect(seedAll(env)).toMatchObject({ inserted: 0, updated: 0, skipped: 25 })
    expect(circuitIndex(env.ctx).index.circuits.map((c) => c.id)).toEqual(bundledSeed().order)

    // Datei geändert → unveränderter Seed-Eintrag wird aktualisiert (rev 2)
    const seed = bundledSeed()
    const notGate = { ...(seed.files.get('not_gate') as CircuitData), difficulty: 2 }
    seed.files.set('not_gate', notGate)
    // Im Team bearbeitet → bleibt, auch wenn die Datei sich ändert
    const orGate = bundledCircuit('or_gate')
    updateCircuit(env.ctx, OWNER, 'or_gate', { circuit: { ...orGate, difficulty: 3 } })
    seed.files.set('or_gate', { ...orGate, difficulty: 2 })
    // Gelöscht → kommt nicht wieder
    deleteCircuit(env.ctx, OWNER, 'xor_gate')
    const r = seedCircuits(env.ctx, seed)
    expect(r.updated).toBe(1)
    expect(getCircuit(env.ctx, 'not_gate')).toMatchObject({ rev: 2, difficulty: 2 })
    expect(getCircuit(env.ctx, 'or_gate')).toMatchObject({ rev: 2, difficulty: 3, edited: 1 })
    expect(getCircuit(env.ctx, 'xor_gate')).toBeUndefined()
    // Ungültige Seed-Datei → übersprungen, nichts kaputt
    seed.files.set('and_gate', { id: 'and_gate' })
    expect(seedCircuits(env.ctx, seed).invalid).toEqual(['and_gate'])
  })
})

describe('public routes (index ETag, rev)', () => {
  async function seeded(): Promise<TestEnv> {
    const env = makeEnv()
    seedAll(env)
    return env
  }

  it('serves the index with a strong ETag and answers 304 on If-None-Match', async () => {
    const env = await seeded()
    const a = await callRoute(env, indexRoute, { url: '/v1/circuits/index' })
    expect(a.status).toBe(200)
    const body = a.body as { version: string, circuits: { id: string, rev: number, minVersion?: string, updatedAt: string }[] }
    expect(body.circuits).toHaveLength(25)
    expect(body.circuits.find((c) => c.id === 'observer_clock')).toMatchObject({ rev: 1, minVersion: '1.11' })
    expect(a.headers.etag).toBe(`"${body.version}"`)
    expect(String(a.headers['cache-control'])).toContain('public')
    const b = await callRoute(env, indexRoute, { url: '/v1/circuits/index', headers: { 'If-None-Match': String(a.headers.etag) } })
    expect(b.status).toBe(304)
    expect(b.body).toBe('')
    // W/-Variante und Listen
    const c = await callRoute(env, indexRoute, { url: '/v1/circuits/index', headers: { 'If-None-Match': `"x", W/${String(a.headers.etag)}` } })
    expect(c.status).toBe(304)
    // Änderung → neue Version, alter ETag gilt nicht mehr
    setCircuitStatus(env.ctx, OWNER, 'not_gate', 'hidden')
    const d = await callRoute(env, indexRoute, { url: '/v1/circuits/index', headers: { 'If-None-Match': String(a.headers.etag) } })
    expect(d.status).toBe(200)
    expect((d.body as typeof body).circuits).toHaveLength(24)
    expect(d.headers.etag).not.toBe(a.headers.etag)
  })

  it('serves a circuit per rev: immutable when current, 404 rev_mismatch otherwise', async () => {
    const env = await seeded()
    const get = (rev?: string, headers?: Record<string, string>) =>
      callRoute(env, circuitRoute, { url: `/v1/circuits/and_gate${rev ? `?rev=${rev}` : ''}`, params: { id: 'and_gate' }, headers })
    const ok = await get('1')
    expect(ok.status).toBe(200)
    expect(ok.headers['cache-control']).toBe('public, max-age=31536000, immutable')
    const json = ok.body as { format: number, id: string, rev: number, texts: Record<string, { name: string }>, palette: object, author: null }
    expect(json).toMatchObject({ format: 1, id: 'and_gate', rev: 1, author: null })
    expect(json.texts.de!.name).toBe('UND-Gatter')
    // exakt das Client-Format (+ rev/updatedAt/author) – besteht die eigene Prüfung
    expect(checkCircuit(json).ok).toBe(true)
    expect((await get('1', { 'If-None-Match': String(ok.headers.etag) })).status).toBe(304)
    // ohne rev: kurz cachebar
    expect((await get()).headers['cache-control']).toBe('public, max-age=60')

    updateCircuit(env.ctx, OWNER, 'and_gate', { circuit: { ...andGate(), difficulty: 3 } })
    const old = await get('1')
    expect(old.status).toBe(404)
    expect(old.error).toMatchObject({ code: 'rev_mismatch', details: { rev: 2 }, headers: { 'Cache-Control': 'no-store' } })
    expect((await get('2')).status).toBe(200)
    expect((await get('3')).error?.code).toBe('rev_mismatch')
    expect((await get('abc')).error?.code).toBe('invalid_request')
    // Status-Wechsel zählt rev hoch; versteckt = 404
    setCircuitStatus(env.ctx, OWNER, 'and_gate', 'hidden')
    expect(getCircuit(env.ctx, 'and_gate')!.rev).toBe(3)
    expect((await get()).error?.code).toBe('circuit_not_found')
    setCircuitStatus(env.ctx, OWNER, 'and_gate', 'published')
    expect(getCircuit(env.ctx, 'and_gate')!.rev).toBe(4)
    expect((await get('4')).status).toBe(200)
    // Unbekannt
    const none = await callRoute(env, circuitRoute, { url: '/v1/circuits/nope', params: { id: 'nope' } })
    expect(none.error?.code).toBe('circuit_not_found')
  })

  it('exports a published circuit as structure .nbt that reads back into the same build', async () => {
    const env = await seeded()
    const r = await callRoute(env, exportRoute, { url: '/v1/circuits/piston_door_2x2/export?format=nbt', params: { id: 'piston_door_2x2' } })
    expect(r.status).toBe(200)
    expect(String(r.headers['content-disposition'])).toContain('piston_door_2x2.nbt')
    const buf = r.body as Buffer
    expect(buf[0]).toBe(0x1f)
    const { root } = readNbt(buf)
    const c = bundledCircuit('piston_door_2x2')
    const info = (checkCircuit(c) as { info: { size: { x: number, y: number, z: number } } }).info
    expect(root.size).toEqual([info.size.x, info.size.y, info.size.z])
    expect((root.blocks as unknown[]).length).toBe(info.size.x * info.size.y * info.size.z)
    expect(root.DataVersion).toBe(1631)
    const names = (root.palette as { Name: string }[]).map((p) => p.Name)
    expect(names).toContain('minecraft:sticky_piston')
    expect(names).toContain('minecraft:stone')
    expect(names).toContain('minecraft:air')
    // Rück-Import = gleicher Bau
    const back = importCircuitFile(buf, { filename: 'door.nbt' })
    expect(back.format).toBe('nbt')
    expect(canonicalContent((checkCircuit(back.circuit) as { info: { cells: never[] } }).info.cells))
      .toBe(canonicalContent((checkCircuit({ ...c, tests: undefined, palette: Object.fromEntries(Object.entries(c.palette).map(([k, v]) => [k, v.replace(/[~?]|@.*$/g, '')])) }) as { info: { cells: never[] } }).info.cells))
    // Kupfer-Birne → neuere DataVersion
    const bulb = await callRoute(env, exportRoute, { url: '/v1/circuits/t_flipflop_copper/export', params: { id: 't_flipflop_copper' } })
    expect(readNbt(bulb.body as Buffer).root.DataVersion).toBe(3953)
    // JSON-Export
    const j = await callRoute(env, exportRoute, { url: '/v1/circuits/and_gate/export?format=json', params: { id: 'and_gate' } })
    expect(JSON.parse(String(j.body))).toMatchObject({ id: 'and_gate', format: 1 })
  })
})

describe('import (.litematic, .schem, .nbt, JSON)', () => {
  const content = (c: CircuitData) => {
    const r = checkCircuit(c)
    if (!r.ok) throw new Error(r.errors.join())
    return canonicalContent(r.info.cells)
  }
  /** Erwarteter Inhalt ohne Anschlüsse/Markierungen (die gibt es in Minecraft-Dateien nicht). */
  const plain = (c: CircuitData) => content({ ...c, tests: undefined, palette: Object.fromEntries(Object.entries(c.palette).map(([k, v]) => [k, v.replace(/[~?]+|@.*$/g, '')])) })

  it('converts litematic files (spanning bits, negative size, block entities ignored)', () => {
    for (const id of ['and_gate', 'xor_gate', 'item_elevator']) {
      const c = bundledCircuit(id)
      const r = importCircuitFile(toLitematic(c, { name: `Mein ${id}` }))
      expect(r.format).toBe('litematic')
      expect(content(r.circuit)).toBe(plain(c))
      expect(r.circuit.texts.en!.name).toBe(`Mein ${id}`)
      expect(r.circuit.id).toBe(`mein_${id}`)
    }
    // 5 Bit je Wert (Werte über Long-Grenzen)
    const c = bundledCircuit('comparator_fill')
    expect(content(importCircuitFile(toLitematic(c, { extraPalette: 20 })).circuit)).toBe(plain(c))
    expect(content(importCircuitFile(toLitematic(c, { negative: true })).circuit)).toBe(plain(c))
  })

  it('converts Sponge schematics v2 and v3 and trims the air around the build', () => {
    const c = bundledCircuit('rs_latch')
    for (const v of [2, 3] as const) {
      const r = importCircuitFile(toSponge(c, v, { pad: 3 }), { filename: 'Latch.schem' })
      expect(r.format).toBe('schem')
      expect(content(r.circuit)).toBe(plain(c))
      expect(r.circuit.texts.en!.name).toBe('Latch')
    }
  })

  it('converts vanilla structure files and the own JSON', () => {
    const c = bundledCircuit('hidden_stairs')
    const nbt = importCircuitFile(exportStructureNbt(c))
    expect(nbt.format).toBe('nbt')
    expect(content(nbt.circuit)).toBe(plain(c))
    const json = importCircuitFile(Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(JSON.stringify(c))]))
    expect(json.format).toBe('json')
    expect(json.circuit.tests).toBeDefined()
    expect(content(json.circuit)).toBe(content(c))
  })

  it('maps similar blocks, keeps only allowed states and reports what it changed', () => {
    const c = bundledCircuit('or_gate')
    const g = gridOf(c)
    const file = toSponge(c, 2, {
      extraBlocks: [
        [0, 0, 0, 'minecraft:oak_button[face=floor,facing=north,powered=true]'],
        [1, 0, 0, 'minecraft:red_concrete'],
        [2, 0, 0, 'minecraft:tnt'],
        [0, 0, 2, 'mod:machine'],
        [1, 0, 2, 'minecraft:redstone_wire[north=side,power=15,south=none,east=none,west=none]'],
      ],
    })
    expect(g.x).toBeGreaterThanOrEqual(3)
    const r = importCircuitFile(file)
    const w = Object.fromEntries(r.warnings.map((x) => [x.block, x]))
    expect(w.oak_button).toMatchObject({ action: 'converted', as: 'stone_button' })
    expect(w.red_concrete).toMatchObject({ action: 'solid' })
    expect(w.tnt).toMatchObject({ action: 'skipped' })
    expect(w['mod:machine']).toMatchObject({ action: 'skipped' })
    // Nur erlaubte Eigenschaften: powered/power/Verbindungen fallen weg
    const specs = Object.values(r.circuit.palette)
    expect(specs).toContain('stone_button[face=floor,facing=north]')
    expect(specs.some((s) => s.includes('power'))).toBe(false)
    expect(specs.some((s) => s.startsWith('redstone_wire['))).toBe(false)
  })

  it('rejects files that are too big, broken or malicious', () => {
    const errCode = (fn: () => unknown) => {
      try {
        fn()
        return 'ok'
      } catch (e) {
        return `${(e as { code: string }).code}:${(e as Error).message}`
      }
    }
    // Bau größer als 16
    const long = { ...small('long'), palette: { '-': 'redstone_wire', A: 'lever' }, layers: [['A'.padEnd(16, '-')]] }
    const big = toSponge(long as unknown as CircuitData, 2, { extraBlocks: [[16 + 4, 0, 0, 'minecraft:stone']], pad: 4 })
    expect(errCode(() => importCircuitFile(big))).toContain('at most 16×16×16')
    // Datei > 2 MB
    expect(errCode(() => importCircuitFile(Buffer.alloc(2 * 1024 * 1024 + 1, 1)))).toContain('payload_too_large')
    // gzip-Bombe: 20 MB Nullen, gepackt ~20 KB
    const bomb = gzipSync(Buffer.alloc(20 * 1024 * 1024))
    expect(bomb.length).toBeLessThan(100_000)
    expect(errCode(() => importCircuitFile(bomb))).toContain('too large when unpacked')
    // Kaputtes gzip
    expect(errCode(() => importCircuitFile(Buffer.from([0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3])))).toContain('invalid_circuit')
    // Riesige Listenlänge ohne Daten (darf keinen Speicher reservieren)
    const hugeList = Buffer.from([TAG.Compound, 0, 0, TAG.List, 0, 1, 0x61, TAG.Int, 0x7f, 0xff, 0xff, 0xff, 0])
    expect(errCode(() => importCircuitFile(hugeList))).toContain('unexpected end of data')
    // Negative Länge
    const neg = Buffer.from([TAG.Compound, 0, 0, TAG.ByteArray, 0, 1, 0x61, 0xff, 0xff, 0xff, 0xff, 0])
    expect(errCode(() => importCircuitFile(neg))).toContain('negative length')
    // Zu tiefe Verschachtelung
    const deep: number[] = [TAG.Compound, 0, 0]
    for (let i = 0; i < 40; i++) deep.push(TAG.Compound, 0, 1, 0x61)
    for (let i = 0; i < 41; i++) deep.push(0)
    expect(errCode(() => importCircuitFile(Buffer.from(deep)))).toContain('nested too deeply')
    // Kein NBT / unbekanntes Format
    expect(errCode(() => importCircuitFile(Buffer.from('hello')))).toContain('invalid_circuit')
    const unknown = Buffer.from([TAG.Compound, 0, 0, TAG.Int, 0, 1, 0x61, 0, 0, 0, 1, 0])
    expect(errCode(() => importCircuitFile(unknown))).toContain('unknown file format')
    // Kaputtes JSON / ungültige Schaltung
    expect(errCode(() => importCircuitFile(Buffer.from('{"id":')))).toContain('not valid JSON')
    expect(errCode(() => importCircuitFile(Buffer.from('{"id":"x","category":"basics","palette":{"A":"tnt"},"layers":[["A"]]}')))).toContain('invalid_circuit')
    // Leere Datei
    expect(errCode(() => importCircuitFile(Buffer.alloc(0)))).toContain('empty')
  })

  it('admin import route needs circuits.manage and reads the raw file', async () => {
    const env = makeEnv()
    const [mod, content] = await players(env, 'Mod', 'Content')
    setMemberRoles(env.ctx, OWNER, mod!.uuid, ['moderator'])
    setMemberRoles(env.ctx, OWNER, content!.uuid, ['content'])
    const token = async (name: string, uuid: string) => (await login(env, name, uuid)).token
    const file = toLitematic(andGate())
    const call = async (tok: string) => callRoute(env, adminImportRoute, {
      method: 'POST', url: '/v1/admin/circuits/import?name=and.litematic', body: file,
      headers: { authorization: `Bearer ${tok}`, 'content-type': 'application/octet-stream' },
    })
    expect((await call(await token('Mod', mod!.uuid))).error?.code).toBe('missing_permission')
    const ok = await call(await token('Content', content!.uuid))
    expect(ok.error).toBeUndefined()
    expect((ok.body as { format: string }).format).toBe('litematic')
    const wrongType = await callRoute(env, adminImportRoute, {
      method: 'POST', url: '/v1/admin/circuits/import', body: file,
      headers: { authorization: `Bearer ${await token('Content', content!.uuid)}`, 'content-type': 'image/png' },
    })
    expect(wrongType.error?.code).toBe('unsupported_media_type')
  })
})

describe('permissions', () => {
  it('circuits.manage: admin, senior moderator and content have it, moderator and supporter do not', async () => {
    const env = makeEnv()
    const [admin, senior, mod, sup, content] = await players(env, 'Admin', 'Senior', 'Mod', 'Sup', 'Content')
    const roles: [typeof admin, string, boolean][] = [[admin, 'admin', true], [senior, 'senior_moderator', true], [mod, 'moderator', false], [sup, 'supporter', false], [content, 'content', true]]
    for (const [u, role, expected] of roles) {
      setMemberRoles(env.ctx, OWNER, u!.uuid, [role])
      expect(can(teamOf(env.ctx, u!.uuid)!, 'circuits.manage'), role).toBe(expected)
    }
    expect(can(OWNER, 'circuits.manage')).toBe(true)
    // Route: Spieler ohne Team → forbidden, Moderator → missing_permission, Content → ok
    const [player] = await players(env, 'Player')
    const list = async (name: string, uuid: string) => callRoute(env, adminListRoute, {
      url: '/v1/admin/circuits', headers: { authorization: `Bearer ${(await login(env, name, uuid)).token}` },
    })
    expect((await list('Player', player!.uuid)).error?.status).toBe(403)
    expect((await list('Mod', mod!.uuid)).error?.code).toBe('missing_permission')
    expect((await list('Content', content!.uuid)).status).toBe(200)
  })
})

describe('team editing', () => {
  it('creates, updates (rev + 1, stale check), lists, hides and deletes with audit', async () => {
    const env = makeEnv()
    await login(env, 'Owner', ADMIN)
    const d = createCircuit(env.ctx, OWNER, { circuit: small('lamp_test') })
    expect(d).toMatchObject({ id: 'lamp_test', rev: 1, status: 'draft', source: 'team' })
    expect(circuitIndex(env.ctx).index.circuits).toHaveLength(0)
    expect(code(() => createCircuit(env.ctx, OWNER, { circuit: small('lamp_test') }))).toBe('circuit_exists')
    expect(code(() => createCircuit(env.ctx, OWNER, { circuit: { ...small('bad'), palette: { A: 'tnt' }, layers: [['A']] } }))).toBe('invalid_circuit')
    const u = updateCircuit(env.ctx, OWNER, 'lamp_test', { circuit: small('lamp_test', '-'), status: 'published', baseRev: 1 })
    expect(u).toMatchObject({ rev: 2, status: 'published' })
    expect(u.publishedAt).not.toBeNull()
    expect(code(() => updateCircuit(env.ctx, OWNER, 'lamp_test', { circuit: small('lamp_test'), baseRev: 1 }))).toBe('stale')
    expect(code(() => updateCircuit(env.ctx, OWNER, 'lamp_test', { circuit: small('other') }))).toBe('invalid_circuit')
    expect(circuitIndex(env.ctx).index.circuits).toEqual([expect.objectContaining({ id: 'lamp_test', rev: 2 })])
    expect(listAdminCircuits(env.ctx, { status: 'published', q: 'lamp' })).toHaveLength(1)
    expect(listAdminCircuits(env.ctx, { status: 'draft' })).toHaveLength(0)
    deleteCircuit(env.ctx, OWNER, 'lamp_test')
    expect(circuitIndex(env.ctx).index.circuits).toHaveLength(0)
    const log = all<{ action: string }>(env.ctx.db, "SELECT action FROM admin_log WHERE ref = 'circuit:lamp_test' ORDER BY id")
    expect(log.map((l) => l.action)).toEqual(['circuit.create', 'circuit.update', 'circuit.delete'])
    // gelöschte ID darf bewusst neu angelegt werden
    expect(createCircuit(env.ctx, OWNER, { circuit: small('lamp_test') }).rev).toBe(1)
  })
})

describe('submissions', () => {
  async function setup() {
    const env = makeEnv()
    seedAll(env)
    await login(env, 'Owner', ADMIN)
    const [alex, bea] = await players(env, 'Alex', 'Bea')
    return { env, alex: alex!, bea: bea! }
  }
  const body = (circuit: unknown, name = 'My lamp') => ({ circuit, name, category: 'basics' as const, description: 'Flip the lever.\nThe lamp turns on.', lang: 'en' as const })

  it('accepts a submission, publishes it with the creator name and tells the submitter', async () => {
    const { env, alex } = await setup()
    const events = listen(env, alex.uuid)
    const s = submitCircuit(env.ctx, alex, body(small('whatever'), 'My lamp'))
    expect(s).toMatchObject({ status: 'pending', name: 'My lamp', circuitId: null })
    expect(events.of('circuit_submission_updated')).toHaveLength(1)
    const r = acceptSubmission(env.ctx, OWNER, s.id, { status: 'published' })
    expect(r.circuit).toMatchObject({ id: 'my_lamp', status: 'published', source: 'submission', author: { uuid: alex.uuid, name: 'Alex' } })
    expect(r.circuit.circuit.texts.en).toEqual({ name: 'My lamp', desc: 'Flip the lever.\nThe lamp turns on.' })
    const ev = events.of('circuit_submission_updated')
    expect(ev.at(-1)!.submission).toMatchObject({ status: 'approved', circuitId: 'my_lamp' })
    expect(circuitIndex(env.ctx).index.circuits.at(-1)).toMatchObject({ id: 'my_lamp', rev: 1 })
    expect(circuitJson(getCircuit(env.ctx, 'my_lamp')!).author).toEqual({ uuid: alex.uuid, name: 'Alex' })
    expect(code(() => acceptSubmission(env.ctx, OWNER, s.id, { status: 'published' }))).toBe('submission_decided')
    expect(mySubmissions(env.ctx, alex.uuid)[0]).toMatchObject({ status: 'approved', circuitId: 'my_lamp' })

    // Konto gelöscht → Name weg, rev + 1, Einreichungen weg
    deleteUser(env.ctx, alex.uuid)
    expect(getCircuit(env.ctx, 'my_lamp')).toMatchObject({ rev: 2, author_uuid: null, author_name: null })
    expect(one(env.ctx.db, 'SELECT 1 AS x FROM circuit_submissions WHERE uuid = ?', alex.uuid)).toBeUndefined()
  })

  it('lets the team edit before accepting and reject with a reason', async () => {
    const { env, alex } = await setup()
    const events = listen(env, alex.uuid)
    const a = submitCircuit(env.ctx, alex, body(small('a'), 'Lamp A'))
    const edited = { ...small('lamp_a_team'), texts: { en: { name: 'Lamp A', desc: 'Edited' }, de: { name: 'Lampe A' } }, difficulty: 2 }
    const r = acceptSubmission(env.ctx, OWNER, a.id, { circuit: edited, status: 'draft' })
    expect(r.circuit).toMatchObject({ id: 'lamp_a_team', status: 'draft', difficulty: 2, names: { en: 'Lamp A', de: 'Lampe A' } })
    expect(circuitIndex(env.ctx).index.circuits.some((c) => c.id === 'lamp_a_team')).toBe(false)

    const b = submitCircuit(env.ctx, alex, body(small('b', '-'), 'Lamp B'))
    const rej = rejectSubmission(env.ctx, OWNER, b.id, 'Please add a description of the inputs.')
    expect(rej).toMatchObject({ status: 'rejected', reason: 'Please add a description of the inputs.' })
    expect(events.of('circuit_submission_updated').at(-1)!.submission).toMatchObject({ status: 'rejected', reason: 'Please add a description of the inputs.' })
    // Aufräumen nach 90 Tagen
    env.clock.advance(91 * DAY)
    expect(sweepCircuitSubmissions(env.ctx)).toBe(2)
    expect(mySubmissions(env.ctx, alex.uuid)).toHaveLength(0)
  })

  it('refuses duplicates, invalid circuits, too many per day and banned uploaders', async () => {
    const { env, alex, bea } = await setup()
    // Duplikat einer veröffentlichten Schaltung (andere Palette-Zeichen, gleicher Bau)
    const and = andGate()
    const renamed = { ...and, palette: Object.fromEntries(Object.entries(and.palette).map(([k, v]) => [k === '#' ? '%' : k, v])), layers: and.layers.map((l) => l.map((r) => r.replace(/#/g, '%'))) }
    expect(code(() => submitCircuit(env.ctx, alex, body(renamed)))).toBe('circuit_duplicate')
    // Duplikat einer offenen Einreichung (auch von jemand anderem)
    submitCircuit(env.ctx, alex, body(small('x1')))
    expect(code(() => submitCircuit(env.ctx, bea, body(small('x2'))))).toBe('circuit_duplicate')
    expect(code(() => submitCircuit(env.ctx, bea, body({ ...small('x'), palette: { A: 'tnt' }, layers: [['A']] })))).toBe('invalid_circuit')
    // Tagesgrenze: 5 je 24 h (Alex hat schon 1)
    for (let i = 0; i < 4; i++) submitCircuit(env.ctx, alex, body(small(`d${i}`, '-'.repeat(i + 1))))
    const err = (() => {
      try {
        submitCircuit(env.ctx, alex, body(small('d9', '------')))
      } catch (e) {
        return e as { code: string, status: number, headers: Record<string, string> }
      }
      return null
    })()
    expect(err).toMatchObject({ code: 'rate_limited', status: 429 })
    expect(Number(err!.headers['Retry-After'])).toBeGreaterThan(0)
    env.clock.advance(DAY + 1000)
    expect(code(() => submitCircuit(env.ctx, alex, body(small('d9', '------'))))).toBe('ok')
    // Upload-Sperre (Moderation v2)
    createSanction(env.ctx, OWNER, { uuid: bea.uuid, kind: 'upload_ban', minutes: 60, reasonCode: 'spam' })
    expect(code(() => submitCircuit(env.ctx, bea, body(small('y', '---------'))))).toBe('sanctioned')
  })

  it('route: Bearer token → 201, text rules and word filter apply', async () => {
    const { env, alex } = await setup()
    const tok = (await login(env, 'Alex', alex.uuid)).token
    const post = (b: unknown) => callRoute(env, submitRoute, {
      method: 'POST', url: '/v1/circuits/submissions', body: JSON.stringify(b),
      headers: { authorization: `Bearer ${tok}`, 'content-type': 'application/json' },
    })
    const ok = await post(body(small('r1'), 'Route lamp'))
    expect(ok.status).toBe(201)
    // Vertrag mit dem TRS Client: id + status oben (docs/circuit-format.md)
    expect(ok.body).toMatchObject({ id: expect.stringMatching(/^cs[0-9a-f]{16}$/), status: 'pending', submission: { status: 'pending' } })
    // Client schickt since/until als null und eine reservierte ID → Server vergibt eine eigene
    const fromClient = await post(body({ ...small('index', '--'), since: null, until: null, server: 'ok' }, 'Index lamp'))
    expect(fromClient.status).toBe(201)
    expect((await post({ ...body(small('r2')), name: '' })).error?.code).toBe('invalid_request')
    expect((await post({ ...body(small('r2')), name: '§cRed' })).error?.code).toBe('invalid_request')
    expect((await post({ ...body(small('r2')), lang: 'fr' })).error?.code).toBe('invalid_request')
    expect((await post({ ...body(small('r2')), extra: 1 })).error?.code).toBe('invalid_request')
    const anon = await callRoute(env, submitRoute, { method: 'POST', url: '/v1/circuits/submissions', body: JSON.stringify(body(small('r3'))), headers: { 'content-type': 'application/json' } })
    expect(anon.error?.status).toBe(401)
  })
})

describe('reports of circuits', () => {
  it('reports a published circuit and the team can hide it', async () => {
    const env = makeEnv()
    seedAll(env)
    await login(env, 'Owner', ADMIN)
    const [alex, bea, mod] = await players(env, 'Alex', 'Bea', 'Mod')
    const s = submitCircuit(env.ctx, alex!, { circuit: small('z'), name: 'Alex lamp', category: 'basics', description: 'Mine', lang: 'en' })
    acceptSubmission(env.ctx, OWNER, s.id, { status: 'published' })
    const rep = createReport(env.ctx, bea!.uuid, { kind: 'circuit', reason: 'inappropriate', circuitId: 'alex_lamp' })
    expect(rep.kind).toBe('circuit')
    const row = one<{ circuit_id: string, target_uuid: string }>(env.ctx.db, 'SELECT circuit_id, target_uuid FROM chat_reports WHERE id = ?', rep.id)!
    expect(row).toEqual({ circuit_id: 'alex_lamp', target_uuid: alex!.uuid })
    expect(code(() => createReport(env.ctx, bea!.uuid, { kind: 'circuit', reason: 'spam', circuitId: 'alex_lamp' }))).toBe('already_reported')
    expect(code(() => createReport(env.ctx, alex!.uuid, { kind: 'circuit', reason: 'spam', circuitId: 'alex_lamp' }))).toBe('cannot_target_self')
    expect(code(() => createReport(env.ctx, bea!.uuid, { kind: 'circuit', reason: 'spam', circuitId: 'gone' }))).toBe('circuit_not_found')
    // Team-Schaltung: ohne Ziel
    const team = createReport(env.ctx, bea!.uuid, { kind: 'circuit', reason: 'other', circuitId: 'not_gate' })
    expect(one<{ target_uuid: string | null }>(env.ctx.db, 'SELECT target_uuid FROM chat_reports WHERE id = ?', team.id)!.target_uuid).toBeNull()
    // Moderator ohne circuits.manage darf nicht verstecken
    setMemberRoles(env.ctx, OWNER, mod!.uuid, ['moderator'])
    expect(code(() => adminReportAction(env.ctx, teamOf(env.ctx, mod!.uuid)!, rep.id, { action: 'hide_circuit' }))).toBe('missing_permission')
    const d = adminReportAction(env.ctx, OWNER, rep.id, { action: 'hide_circuit' })
    expect(d.circuitId).toBe('alex_lamp')
    expect(d.status).toBe('resolved')
    expect(getCircuit(env.ctx, 'alex_lamp')!.status).toBe('hidden')
    expect(circuitIndex(env.ctx).index.circuits.some((c) => c.id === 'alex_lamp')).toBe(false)
    expect(getUser(env.ctx, alex!.uuid)).toBeDefined()
    run(env.ctx.db, 'DELETE FROM chat_reports')
  })
})
