import { randomBytes } from 'node:crypto'
import { z } from 'zod'
import {
  CIRCUIT_CATEGORIES,
  CIRCUIT_ID,
  CIRCUIT_LANGS,
  MAX_DESC,
  RESERVED_CIRCUIT_IDS,
  MAX_NAME,
  canonicalContent,
  checkCircuit,
  circuitMaterials,
  slugCircuitId,
  type CircuitCategory,
  type CircuitData,
  type CircuitInfo,
  type CircuitLang,
} from '../../shared/circuits'
import { audit } from './audit'
import type { AppContext } from './context'
import { all, one, run, tx, type Db } from './db'
import { ApiError, badRequest, conflict, notFound } from './errors'
import { sha256Hex } from './ids'
import { assertNotSanctioned } from './sanctions'
import { applyWordFilter, sanitizeText, textLength } from './safety'
import type { Staff } from './team'
import { getUser } from './users'

/**
 * Schaltungs-Bibliothek (API.md §25): Schaltungen liegen auf dem Server und kommen ohne Mod-Update in den TRS Client.
 * Öffentlich: Index mit rev je Schaltung (ETag), Schaltung je rev unveränderlich. Team (`circuits.manage`): Editor,
 * Import/Export, Veröffentlichen/Verstecken/Löschen. Spieler: Einreichungen mit Prüfung durch das Team.
 *
 * rev zählt bei JEDER Änderung einer Schaltung hoch (Inhalt, Texte, Status, Ersteller entfernt). Gelöschte IDs bleiben
 * als Grabstein, damit der Seed sie beim nächsten Start nicht wieder anlegt.
 */

const DAY = 86_400_000
/** Entschiedene Einreichungen werden so lange nach der Entscheidung gelöscht (die Schaltung selbst bleibt). */
export const SUBMISSION_RETENTION_MS = 90 * DAY
export const MAX_REJECT_REASON = 500

export type CircuitStatus = 'draft' | 'published' | 'hidden'
export type CircuitSource = 'seed' | 'team' | 'submission'
export type SubmissionStatus = 'pending' | 'approved' | 'rejected'
export type SubmissionFormat = 'json' | 'litematic' | 'schem' | 'nbt'

export interface CircuitRow {
  id: string
  rev: number
  status: CircuitStatus
  category: CircuitCategory
  difficulty: number
  min_version: string | null
  max_version: string | null
  sort: number
  data: string
  content_hash: string
  author_uuid: string | null
  author_name: string | null
  source: CircuitSource
  seed_hash: string | null
  edited: number
  created_at: number
  updated_at: number
  published_at: number | null
  created_by: string
  updated_by: string
}

export interface SubmissionRow {
  id: string
  uuid: string
  name: string
  title: string
  description: string
  category: CircuitCategory
  lang: CircuitLang
  data: string
  content_hash: string
  source_format: SubmissionFormat
  status: SubmissionStatus
  reason: string | null
  circuit_id: string | null
  created_at: number
  updated_at: number
  decided_at: number | null
  decided_by: string | null
}

const iso = (t: number) => new Date(t).toISOString()
const isoOrNull = (t: number | null) => (t === null ? null : iso(t))

// ---------------------------------------------------------------- Cache-Generation

/** Zählt jede Änderung; Index und Website-Liste werden daran zwischengespeichert. */
const generations = new WeakMap<Db, number>()
const gen = (db: Db) => generations.get(db) ?? 0
function bump(db: Db): void {
  generations.set(db, gen(db) + 1)
  indexCache.delete(db)
}

// ---------------------------------------------------------------- Prüfen

/** Prüft eine Schaltung; Fehler → 400 `invalid_circuit` mit `details.errors`. */
export function validateCircuit(raw: unknown): { circuit: CircuitData, info: CircuitInfo, hash: string } {
  const json = JSON.stringify(raw ?? null)
  if (json.length > 256 * 1024) throw new ApiError(413, 'payload_too_large', 'Circuit JSON is larger than 256 KB')
  const r = checkCircuit(raw)
  if (!r.ok) throw badRequest('invalid_circuit', 'The circuit is not valid', { errors: r.errors })
  return { circuit: r.circuit, info: r.info, hash: contentHash(r.info) }
}

export function contentHash(info: CircuitInfo): string {
  return sha256Hex(canonicalContent(info.cells))
}

// ---------------------------------------------------------------- Sichten

export interface CircuitAuthor {
  uuid: string
  name: string
}

/** Genau das, was der Client lädt (`GET /v1/circuits/{id}?rev=`): Client-Format + rev, updatedAt, author. */
export type CircuitJson = CircuitData & { rev: number, updatedAt: string, author: CircuitAuthor | null }

export function circuitJson(r: CircuitRow): CircuitJson {
  const data = JSON.parse(r.data) as CircuitData
  const { format, id, ...rest } = data
  return {
    format,
    id,
    rev: r.rev,
    ...rest,
    updatedAt: iso(r.updated_at),
    author: r.author_uuid && r.author_name ? { uuid: r.author_uuid, name: r.author_name } : null,
  }
}

export interface IndexEntry {
  id: string
  rev: number
  updatedAt: string
  minVersion?: string
  maxVersion?: string
}

export interface CircuitIndex {
  version: string
  circuits: IndexEntry[]
}

const indexCache = new WeakMap<Db, { index: CircuitIndex, etag: string }>()

/** Öffentlicher Index (nur veröffentlichte), zwischengespeichert bis zur nächsten Änderung. */
export function circuitIndex(ctx: AppContext): { index: CircuitIndex, etag: string } {
  const cached = indexCache.get(ctx.db)
  if (cached) return cached
  const rows = all<Pick<CircuitRow, 'id' | 'rev' | 'updated_at' | 'min_version' | 'max_version'>>(
    ctx.db, "SELECT id, rev, updated_at, min_version, max_version FROM circuits WHERE status = 'published' ORDER BY sort, id",
  )
  const circuits = rows.map((r) => ({
    id: r.id,
    rev: r.rev,
    updatedAt: iso(r.updated_at),
    ...(r.min_version ? { minVersion: r.min_version } : {}),
    ...(r.max_version ? { maxVersion: r.max_version } : {}),
  }))
  const version = sha256Hex(JSON.stringify(circuits)).slice(0, 32)
  const out = { index: { version, circuits }, etag: `"${version}"` }
  indexCache.set(ctx.db, out)
  return out
}

export function getCircuit(ctx: AppContext, id: string): CircuitRow | undefined {
  if (!CIRCUIT_ID.test(id)) return undefined
  return one<CircuitRow>(ctx.db, 'SELECT * FROM circuits WHERE id = ?', id)
}

export function publishedCircuit(ctx: AppContext, id: string): CircuitRow | undefined {
  const r = getCircuit(ctx, id)
  return r?.status === 'published' ? r : undefined
}

/** Website: veröffentlichte Schaltung mit Maßen, Materialliste und Versionen. */
export interface SiteCircuitView {
  circuit: CircuitJson
  size: { x: number, y: number, z: number }
  blockCount: number
  materials: { key: string, item: string, count: number }[]
  minVersion: string
  maxVersion: string | null
  publishedAt: string | null
}

export function siteCircuitView(r: CircuitRow): SiteCircuitView {
  const circuit = circuitJson(r)
  const check = checkCircuit(JSON.parse(r.data))
  const info = check.ok ? check.info : { size: { x: 0, y: 0, z: 0 }, cells: [], blockCount: 0, since: '1.8', markers: [] }
  return {
    circuit,
    size: info.size,
    blockCount: info.blockCount,
    materials: circuitMaterials(info.cells),
    minVersion: r.min_version ?? info.since,
    maxVersion: r.max_version,
    publishedAt: isoOrNull(r.published_at),
  }
}

export function siteCircuits(ctx: AppContext): SiteCircuitView[] {
  return all<CircuitRow>(ctx.db, "SELECT * FROM circuits WHERE status = 'published' ORDER BY sort, id").map(siteCircuitView)
}

export interface AdminCircuitSummary {
  id: string
  rev: number
  status: CircuitStatus
  category: CircuitCategory
  difficulty: number
  names: Partial<Record<string, string>>
  author: CircuitAuthor | null
  source: CircuitSource
  edited: boolean
  size: { x: number, y: number, z: number }
  blockCount: number
  minVersion: string | null
  maxVersion: string | null
  sort: number
  createdAt: string
  updatedAt: string
  publishedAt: string | null
}

export function adminSummary(r: CircuitRow): AdminCircuitSummary {
  const data = JSON.parse(r.data) as CircuitData
  const check = checkCircuit(data)
  const names: Record<string, string> = {}
  for (const [l, t] of Object.entries(data.texts ?? {})) if (t.name) names[l] = t.name
  return {
    id: r.id,
    rev: r.rev,
    status: r.status,
    category: r.category,
    difficulty: r.difficulty,
    names,
    author: r.author_uuid && r.author_name ? { uuid: r.author_uuid, name: r.author_name } : null,
    source: r.source,
    edited: r.edited === 1,
    size: check.ok ? check.info.size : { x: 0, y: 0, z: 0 },
    blockCount: check.ok ? check.info.blockCount : 0,
    minVersion: r.min_version,
    maxVersion: r.max_version,
    sort: r.sort,
    createdAt: iso(r.created_at),
    updatedAt: iso(r.updated_at),
    publishedAt: isoOrNull(r.published_at),
  }
}

export interface AdminCircuitDetail extends AdminCircuitSummary {
  circuit: CircuitJson
}

export function adminDetail(r: CircuitRow): AdminCircuitDetail {
  return { ...adminSummary(r), circuit: circuitJson(r) }
}

export const circuitListQuery = z.strictObject({
  status: z.enum(['draft', 'published', 'hidden', 'all']).default('all'),
  category: z.enum(CIRCUIT_CATEGORIES).optional(),
  q: z.string().max(64).optional(),
})

/** Liste für den Team-Bereich – mit Schaltung (für die Vorschaubilder; höchstens einige hundert kleine Einträge). */
export function listAdminCircuits(ctx: AppContext, q: z.output<typeof circuitListQuery>): AdminCircuitDetail[] {
  const rows = q.status === 'all'
    ? all<CircuitRow>(ctx.db, 'SELECT * FROM circuits ORDER BY sort, id')
    : all<CircuitRow>(ctx.db, 'SELECT * FROM circuits WHERE status = ? ORDER BY sort, id', q.status)
  const needle = q.q?.trim().toLowerCase() ?? ''
  return rows
    .filter((r) => !q.category || r.category === q.category)
    .map(adminDetail)
    .filter((s) => !needle || s.id.includes(needle) || Object.values(s.names).some((n) => n?.toLowerCase().includes(needle)) || (s.author?.name.toLowerCase().includes(needle) ?? false))
}

// ---------------------------------------------------------------- Seed (mitgelieferte Schaltungen)

export interface SeedFiles {
  /** Reihenfolge aus `assets/circuits/index.json`. */
  order: string[]
  files: Map<string, unknown>
}

/**
 * Spielt die mitgelieferten Schaltungen ein (idempotent): fehlende anlegen (veröffentlicht), unveränderte Seed-Einträge
 * bei geänderter Datei aktualisieren (rev + 1). Im Admin bearbeitete (`edited`), fremde und gelöschte IDs bleiben
 * unberührt. Rückgabe: angelegt/aktualisiert/übersprungen.
 */
export function seedCircuits(ctx: AppContext, seed: SeedFiles): { inserted: number, updated: number, skipped: number, invalid: string[] } {
  const result = { inserted: 0, updated: 0, skipped: 0, invalid: [] as string[] }
  const t = ctx.now()
  tx(ctx.db, () => {
    seed.order.forEach((id, i) => {
      const raw = seed.files.get(id)
      const check = raw === undefined ? null : checkCircuit(raw)
      if (!check?.ok || check.circuit.id !== id) {
        result.invalid.push(id)
        return
      }
      const { circuit, info } = check
      const seedHash = sha256Hex(JSON.stringify(circuit))
      if (one(ctx.db, 'SELECT 1 AS x FROM circuit_tombstones WHERE id = ?', id)) {
        result.skipped++
        return
      }
      const row = getCircuit(ctx, id)
      const minVersion = info.since
      if (!row) {
        run(
          ctx.db,
          `INSERT INTO circuits (id, rev, status, category, difficulty, min_version, max_version, sort, data, content_hash, author_uuid,
             author_name, source, seed_hash, edited, created_at, updated_at, published_at, created_by, updated_by)
           VALUES (?, 1, 'published', ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 'seed', ?, 0, ?, ?, ?, 'system', 'system')`,
          id, circuit.category, circuit.difficulty, minVersion, circuit.until ?? null, (i + 1) * 10, JSON.stringify(circuit),
          contentHash(info), seedHash, t, t, t,
        )
        result.inserted++
      } else if (row.source === 'seed' && row.edited === 0 && row.seed_hash !== seedHash) {
        run(
          ctx.db,
          `UPDATE circuits SET rev = rev + 1, category = ?, difficulty = ?, min_version = ?, max_version = ?, data = ?, content_hash = ?,
             seed_hash = ?, updated_at = ?, updated_by = 'system' WHERE id = ?`,
          circuit.category, circuit.difficulty, minVersion, circuit.until ?? null, JSON.stringify(circuit), contentHash(info), seedHash, t, id,
        )
        result.updated++
      } else {
        result.skipped++
      }
    })
  })
  if (result.inserted || result.updated) bump(ctx.db)
  return result
}

// ---------------------------------------------------------------- Team: anlegen, ändern, Status, löschen

export const adminCircuitBody = z.strictObject({
  circuit: z.unknown(),
  status: z.enum(['draft', 'published', 'hidden']).optional(),
  sort: z.number().int().min(0).max(1_000_000).optional(),
  /** Erwartete rev (Schutz gegen gleichzeitiges Bearbeiten) → sonst 409 `stale`. */
  baseRev: z.number().int().min(1).optional(),
})

const auditRef = (id: string) => `circuit:${id}`

function assertIdFree(ctx: AppContext, id: string): void {
  if (getCircuit(ctx, id)) throw conflict('circuit_exists', 'A circuit with this id already exists')
}

/** Freie ID aus einem Wunsch (`and_gate`, `and_gate_2`, …) – auch gelöschte IDs sind vergeben. */
export function freeCircuitId(ctx: AppContext, wish: string): string {
  const base = slugCircuitId(wish).slice(0, 44)
  for (let n = 1; n < 1000; n++) {
    const id = n === 1 ? base : `${base}_${n}`
    if (!getCircuit(ctx, id) && !one(ctx.db, 'SELECT 1 AS x FROM circuit_tombstones WHERE id = ?', id)) return id
  }
  return `${base.slice(0, 30)}_${randomBytes(4).toString('hex')}`
}

export function createCircuit(ctx: AppContext, actor: Staff, body: z.output<typeof adminCircuitBody>): AdminCircuitDetail {
  const { circuit, info, hash } = validateCircuit(body.circuit)
  const t = ctx.now()
  const status = body.status ?? 'draft'
  tx(ctx.db, () => {
    assertIdFree(ctx, circuit.id)
    // Eine gelöschte ID darf bewusst neu vergeben werden (Grabstein weg).
    run(ctx.db, 'DELETE FROM circuit_tombstones WHERE id = ?', circuit.id)
    const sort = body.sort ?? (one<{ s: number | null }>(ctx.db, 'SELECT MAX(sort) AS s FROM circuits')!.s ?? 0) + 10
    run(
      ctx.db,
      `INSERT INTO circuits (id, rev, status, category, difficulty, min_version, max_version, sort, data, content_hash, author_uuid,
         author_name, source, seed_hash, edited, created_at, updated_at, published_at, created_by, updated_by)
       VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 'team', NULL, 1, ?, ?, ?, ?, ?)`,
      circuit.id, status, circuit.category, circuit.difficulty, info.since, circuit.until ?? null, sort, JSON.stringify(circuit), hash,
      t, t, status === 'published' ? t : null, actor.uuid, actor.uuid,
    )
    audit(ctx, actor.uuid, 'circuit.create', null, `${circuit.id} (${status})`, auditRef(circuit.id))
  })
  bump(ctx.db)
  return adminDetail(getCircuit(ctx, circuit.id)!)
}

function assertBaseRev(row: CircuitRow, baseRev: number | undefined): void {
  if (baseRev !== undefined && baseRev !== row.rev) {
    throw new ApiError(409, 'stale', 'The circuit was changed in the meantime – reload it', { rev: row.rev })
  }
}

export function updateCircuit(ctx: AppContext, actor: Staff, id: string, body: z.output<typeof adminCircuitBody>): AdminCircuitDetail {
  const row = getCircuit(ctx, id)
  if (!row) throw notFound('circuit_not_found', 'Circuit not found')
  assertBaseRev(row, body.baseRev)
  const { circuit, info, hash } = validateCircuit(body.circuit)
  if (circuit.id !== id) throw badRequest('invalid_circuit', 'The id of a circuit cannot be changed', { errors: ['id cannot be changed'] })
  const status = body.status ?? row.status
  const t = ctx.now()
  tx(ctx.db, () => {
    run(
      ctx.db,
      `UPDATE circuits SET rev = rev + 1, status = ?, category = ?, difficulty = ?, min_version = ?, max_version = ?, sort = ?, data = ?,
         content_hash = ?, edited = 1, updated_at = ?, updated_by = ?, published_at = COALESCE(published_at, ?) WHERE id = ?`,
      status, circuit.category, circuit.difficulty, info.since, circuit.until ?? null, body.sort ?? row.sort, JSON.stringify(circuit), hash,
      t, actor.uuid, status === 'published' ? t : null, id,
    )
    audit(ctx, actor.uuid, 'circuit.update', row.author_uuid, `${id} rev ${row.rev + 1}${status !== row.status ? ` → ${status}` : ''}`, auditRef(id))
  })
  bump(ctx.db)
  return adminDetail(getCircuit(ctx, id)!)
}

export const circuitStatusBody = z.strictObject({ status: z.enum(['draft', 'published', 'hidden']), baseRev: z.number().int().min(1).optional() })

export function setCircuitStatus(ctx: AppContext, actor: Staff | string, id: string, status: CircuitStatus, baseRev?: number): AdminCircuitDetail {
  const who = typeof actor === 'string' ? actor : actor.uuid
  const row = getCircuit(ctx, id)
  if (!row) throw notFound('circuit_not_found', 'Circuit not found')
  assertBaseRev(row, baseRev)
  if (row.status !== status) {
    const t = ctx.now()
    tx(ctx.db, () => {
      run(
        ctx.db,
        'UPDATE circuits SET rev = rev + 1, status = ?, edited = 1, updated_at = ?, updated_by = ?, published_at = COALESCE(published_at, ?) WHERE id = ?',
        status, t, who, status === 'published' ? t : null, id,
      )
      audit(ctx, who, `circuit.${status === 'published' ? 'publish' : status === 'hidden' ? 'hide' : 'draft'}`, row.author_uuid, id, auditRef(id))
    })
    bump(ctx.db)
  }
  return adminDetail(getCircuit(ctx, id)!)
}

export function deleteCircuit(ctx: AppContext, actor: Staff, id: string): void {
  const row = getCircuit(ctx, id)
  if (!row) throw notFound('circuit_not_found', 'Circuit not found')
  tx(ctx.db, () => {
    run(ctx.db, 'DELETE FROM circuits WHERE id = ?', id)
    run(ctx.db, 'INSERT OR REPLACE INTO circuit_tombstones (id, deleted_at, deleted_by) VALUES (?, ?, ?)', id, ctx.now(), actor.uuid)
    audit(ctx, actor.uuid, 'circuit.delete', row.author_uuid, id, auditRef(id))
  })
  bump(ctx.db)
}

/** Konto gelöscht: Ersteller-Name aus seinen Schaltungen entfernen (rev + 1, damit Clients neu laden). */
export function forgetCircuitAuthor(ctx: AppContext, uuid: string): number {
  const n = run(
    ctx.db,
    'UPDATE circuits SET author_uuid = NULL, author_name = NULL, rev = rev + 1, updated_at = ?, updated_by = ? WHERE author_uuid = ?',
    ctx.now(), 'system', uuid,
  )
  if (n) bump(ctx.db)
  return n
}

// ---------------------------------------------------------------- Einreichungen (Spieler)

const cleanText = (max: number, multiline: boolean) =>
  z.string().max(max * 4)
    .transform((s) => (multiline ? sanitizeText(s).trim() : sanitizeText(s).replace(/\s*\n\s*/g, ' ').trim()))
    .refine((s) => s.length > 0, 'must not be empty')
    .refine((s) => textLength(s) <= max, `at most ${max} characters`)
    .refine((s) => !s.includes('§'), 'must not contain formatting codes')

export const submissionBody = z.strictObject({
  circuit: z.unknown(),
  name: cleanText(MAX_NAME, false),
  category: z.enum(CIRCUIT_CATEGORIES),
  description: cleanText(MAX_DESC, true),
  lang: z.enum(CIRCUIT_LANGS),
  /** Woher die Schaltung stammt (nur Statistik/Anzeige im Team). */
  format: z.enum(['json', 'litematic', 'schem', 'nbt']).optional(),
})

export interface MySubmissionView {
  id: string
  name: string
  category: CircuitCategory
  lang: CircuitLang
  status: SubmissionStatus
  /** Grund der Ablehnung (vom Team). */
  reason: string | null
  /** Veröffentlichte Schaltung nach dem Annehmen. */
  circuitId: string | null
  createdAt: string
  updatedAt: string
  decidedAt: string | null
}

export function mySubmissionView(r: SubmissionRow): MySubmissionView {
  return {
    id: r.id,
    name: r.title,
    category: r.category,
    lang: r.lang,
    status: r.status,
    reason: r.reason,
    circuitId: r.status === 'approved' ? r.circuit_id : null,
    createdAt: iso(r.created_at),
    updatedAt: iso(r.updated_at),
    decidedAt: isoOrNull(r.decided_at),
  }
}

export function submissionLimits(ctx: AppContext, uuid: string): { today: number, maxPerDay: number } {
  const today = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM circuit_submissions WHERE uuid = ? AND created_at > ?', uuid, ctx.now() - DAY)!.n
  return { today, maxPerDay: ctx.config.limits.maxCircuitSubmissionsPerDay }
}

/** Gleicher Inhalt schon veröffentlicht/angelegt oder eingereicht (offen/angenommen)? */
function findDuplicate(ctx: AppContext, hash: string): { kind: 'circuit' | 'submission', id: string } | null {
  const c = one<{ id: string, status: CircuitStatus }>(ctx.db, 'SELECT id, status FROM circuits WHERE content_hash = ? LIMIT 1', hash)
  if (c) return { kind: 'circuit', id: c.id }
  const s = one<{ id: string }>(ctx.db, "SELECT id FROM circuit_submissions WHERE content_hash = ? AND status IN ('pending', 'approved') LIMIT 1", hash)
  return s ? { kind: 'submission', id: s.id } : null
}

/**
 * Schaltung einreichen (§25.5). Reihenfolge der Prüfungen: Upload-Sperre (403 `sanctioned`) → Tagesgrenze (429
 * `rate_limited`) → Schaltung (400 `invalid_circuit`) → Duplikat (409 `circuit_duplicate`).
 */
export function submitCircuit(ctx: AppContext, submitter: { uuid: string, name: string }, body: z.output<typeof submissionBody>): MySubmissionView {
  assertNotSanctioned(ctx, submitter.uuid, 'upload_ban')
  const lim = submissionLimits(ctx, submitter.uuid)
  if (lim.today >= lim.maxPerDay) {
    const oldest = one<{ at: number }>(
      ctx.db, 'SELECT created_at AS at FROM circuit_submissions WHERE uuid = ? AND created_at > ? ORDER BY created_at LIMIT 1', submitter.uuid, ctx.now() - DAY,
    )
    const retry = Math.max(1, Math.ceil(((oldest?.at ?? ctx.now()) + DAY - ctx.now()) / 1000))
    throw new ApiError(429, 'rate_limited', `At most ${lim.maxPerDay} submissions per day`, { retryAfter: retry, max: lim.maxPerDay }, { 'Retry-After': String(retry) })
  }
  const name = applyWordFilter(ctx, body.name)
  const description = applyWordFilter(ctx, body.description)
  // Id, Kategorie und Texte bestimmt die Einreichung; der Rest (Palette, Schichten, Tests) kommt aus der Datei.
  const raw = typeof body.circuit === 'object' && body.circuit !== null && !Array.isArray(body.circuit) ? body.circuit as Record<string, unknown> : {}
  const { circuit, hash } = validateCircuit({
    ...raw,
    id: typeof raw.id === 'string' && CIRCUIT_ID.test(raw.id) && !RESERVED_CIRCUIT_IDS.has(raw.id) ? raw.id : slugCircuitId(name),
    category: body.category,
    texts: { [body.lang]: { name, desc: description } },
  })
  const dup = findDuplicate(ctx, hash)
  if (dup) throw new ApiError(409, 'circuit_duplicate', 'This circuit is already in the library or was already submitted', dup.kind === 'circuit' ? { circuitId: dup.id } : {})
  const id = `cs${randomBytes(8).toString('hex')}`
  const t = ctx.now()
  run(
    ctx.db,
    `INSERT INTO circuit_submissions (id, uuid, name, title, description, category, lang, data, content_hash, source_format, status,
       reason, circuit_id, created_at, updated_at, decided_at, decided_by)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', NULL, NULL, ?, ?, NULL, NULL)`,
    id, submitter.uuid, submitter.name, name, description, body.category, body.lang, JSON.stringify(circuit), hash, body.format ?? 'json', t, t,
  )
  const view = mySubmissionView(getSubmission(ctx, id)!)
  ctx.events.publish(submitter.uuid, { type: 'circuit_submission_updated', submission: view }, { meOnly: true })
  return view
}

export function getSubmission(ctx: AppContext, id: string): SubmissionRow | undefined {
  if (!/^cs[0-9a-f]{16}$/.test(id)) return undefined
  return one<SubmissionRow>(ctx.db, 'SELECT * FROM circuit_submissions WHERE id = ?', id)
}

export function mySubmissions(ctx: AppContext, uuid: string): MySubmissionView[] {
  return all<SubmissionRow>(ctx.db, 'SELECT * FROM circuit_submissions WHERE uuid = ? ORDER BY created_at DESC LIMIT 100', uuid).map(mySubmissionView)
}

// ---------------------------------------------------------------- Einreichungen (Team)

export interface AdminSubmissionView extends MySubmissionView {
  submitter: CircuitAuthor
  description: string
  format: SubmissionFormat
  circuit: CircuitData
  size: { x: number, y: number, z: number }
  blockCount: number
  decidedBy: string | null
  /** Offene bzw. angenommene Einreichungen desselben Kontos (Überblick). */
  submitterStats: { pending: number, approved: number, rejected: number }
}

export function adminSubmissionView(ctx: AppContext, r: SubmissionRow): AdminSubmissionView {
  const data = JSON.parse(r.data) as CircuitData
  const check = checkCircuit(data)
  const stats = all<{ status: SubmissionStatus, n: number }>(ctx.db, 'SELECT status, COUNT(*) AS n FROM circuit_submissions WHERE uuid = ? GROUP BY status', r.uuid)
  const count = (s: SubmissionStatus) => stats.find((x) => x.status === s)?.n ?? 0
  return {
    ...mySubmissionView(r),
    circuitId: r.circuit_id,
    submitter: { uuid: r.uuid, name: getUser(ctx, r.uuid)?.name ?? r.name },
    description: r.description,
    format: r.source_format,
    circuit: data,
    size: check.ok ? check.info.size : { x: 0, y: 0, z: 0 },
    blockCount: check.ok ? check.info.blockCount : 0,
    decidedBy: r.decided_by,
    submitterStats: { pending: count('pending'), approved: count('approved'), rejected: count('rejected') },
  }
}

export const submissionListQuery = z.strictObject({
  status: z.enum(['pending', 'approved', 'rejected', 'all']).default('pending'),
})

export function listAdminSubmissions(ctx: AppContext, status: SubmissionStatus | 'all'): AdminSubmissionView[] {
  const rows = status === 'all'
    ? all<SubmissionRow>(ctx.db, 'SELECT * FROM circuit_submissions ORDER BY created_at DESC LIMIT 200')
    : all<SubmissionRow>(ctx.db, `SELECT * FROM circuit_submissions WHERE status = ? ORDER BY created_at ${status === 'pending' ? 'ASC' : 'DESC'} LIMIT 200`, status)
  return rows.map((r) => adminSubmissionView(ctx, r))
}

/** Zähler für Übersicht und Seitenleiste. */
export function circuitCounts(ctx: AppContext): { pendingSubmissions: number, published: number, total: number } {
  const c = one<{ total: number, published: number }>(ctx.db, "SELECT COUNT(*) AS total, SUM(status = 'published') AS published FROM circuits")!
  return { pendingSubmissions: pendingSubmissionCount(ctx), published: c.published ?? 0, total: c.total }
}

export function pendingSubmissionCount(ctx: AppContext): number {
  return one<{ n: number }>(ctx.db, "SELECT COUNT(*) AS n FROM circuit_submissions WHERE status = 'pending'")!.n
}

export const acceptSubmissionBody = z.strictObject({
  /** Bearbeitete Schaltung (Editor); fehlt = so wie eingereicht. */
  circuit: z.unknown().optional(),
  status: z.enum(['published', 'draft']).default('published'),
  sort: z.number().int().min(0).max(1_000_000).optional(),
})

export const rejectSubmissionBody = z.strictObject({
  reason: z.string().max(MAX_REJECT_REASON * 4).transform((s) => sanitizeText(s).trim()).refine((s) => s.length >= 3 && textLength(s) <= MAX_REJECT_REASON, `3–${MAX_REJECT_REASON} characters`),
})

function assertPending(r: SubmissionRow | undefined): SubmissionRow {
  if (!r) throw notFound('submission_not_found', 'Submission not found')
  if (r.status !== 'pending') throw conflict('submission_decided', 'This submission was already decided')
  return r
}

/** Annehmen: legt die Schaltung mit dem Namen des Erstellers an (Standard: veröffentlicht). */
export function acceptSubmission(ctx: AppContext, actor: Staff, id: string, body: z.output<typeof acceptSubmissionBody>): { submission: AdminSubmissionView, circuit: AdminCircuitDetail } {
  const s = assertPending(getSubmission(ctx, id))
  const base = JSON.parse(s.data) as CircuitData
  const wanted = body.circuit ?? { ...base, id: freeCircuitId(ctx, s.title) }
  const { circuit, info, hash } = validateCircuit(wanted)
  const t = ctx.now()
  const authorName = getUser(ctx, s.uuid)?.name ?? s.name
  tx(ctx.db, () => {
    assertIdFree(ctx, circuit.id)
    run(ctx.db, 'DELETE FROM circuit_tombstones WHERE id = ?', circuit.id)
    const sort = body.sort ?? (one<{ s: number | null }>(ctx.db, 'SELECT MAX(sort) AS s FROM circuits')!.s ?? 0) + 10
    run(
      ctx.db,
      `INSERT INTO circuits (id, rev, status, category, difficulty, min_version, max_version, sort, data, content_hash, author_uuid,
         author_name, source, seed_hash, edited, created_at, updated_at, published_at, created_by, updated_by)
       VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'submission', NULL, 1, ?, ?, ?, ?, ?)`,
      circuit.id, body.status, circuit.category, circuit.difficulty, info.since, circuit.until ?? null, sort, JSON.stringify(circuit), hash,
      s.uuid, authorName, t, t, body.status === 'published' ? t : null, actor.uuid, actor.uuid,
    )
    run(
      ctx.db,
      "UPDATE circuit_submissions SET status = 'approved', circuit_id = ?, reason = NULL, updated_at = ?, decided_at = ?, decided_by = ? WHERE id = ?",
      circuit.id, t, t, actor.uuid, id,
    )
    audit(ctx, actor.uuid, 'circuit_submission.accept', s.uuid, `${id} → ${circuit.id} (${body.status})`, auditRef(circuit.id))
  })
  bump(ctx.db)
  const row = getSubmission(ctx, id)!
  ctx.events.publish(s.uuid, { type: 'circuit_submission_updated', submission: mySubmissionView(row) }, { meOnly: true })
  return { submission: adminSubmissionView(ctx, row), circuit: adminDetail(getCircuit(ctx, circuit.id)!) }
}

export function rejectSubmission(ctx: AppContext, actor: Staff, id: string, reason: string): AdminSubmissionView {
  const s = assertPending(getSubmission(ctx, id))
  const t = ctx.now()
  tx(ctx.db, () => {
    run(ctx.db, "UPDATE circuit_submissions SET status = 'rejected', reason = ?, updated_at = ?, decided_at = ?, decided_by = ? WHERE id = ?", reason, t, t, actor.uuid, id)
    audit(ctx, actor.uuid, 'circuit_submission.reject', s.uuid, id, `circuit-submission:${id}`)
  })
  const row = getSubmission(ctx, id)!
  ctx.events.publish(s.uuid, { type: 'circuit_submission_updated', submission: mySubmissionView(row) }, { meOnly: true })
  return adminSubmissionView(ctx, row)
}

/** Löschfristen: entschiedene Einreichungen nach {@link SUBMISSION_RETENTION_MS}. */
export function sweepCircuitSubmissions(ctx: AppContext): number {
  return run(ctx.db, "DELETE FROM circuit_submissions WHERE status <> 'pending' AND decided_at < ?", ctx.now() - SUBMISSION_RETENTION_MS)
}
