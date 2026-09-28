import { z } from 'zod'
import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { MAX_CLOCK_SKEW_MS } from './sync'

/**
 * Notizen-Sync (§17.5): Notizen des TRS Clients je Server bzw. Einzelspielerwelt. Je Notiz gewinnt der neuere
 * `updatedAt` (gleich alt = überschreiben). Jede Änderung bekommt die nächste Nummer des Änderungszählers des Kontos;
 * der Cursor ist diese Nummer. Grabsteine bleiben mindestens 90 Tage; wer mit einem älteren Cursor kommt, bekommt die
 * komplette Liste mit `reset: true`.
 */

export const MAX_NOTES_PER_WORLD = 200
export const MAX_NOTES_PER_ACCOUNT = 2000
export const MAX_NOTE_TITLE = 64
export const MAX_NOTE_TEXT = 20_000
export const MAX_NOTE_WORLD_NAME = 64
export const MAX_NOTE_ADDRESS = 255
export const NOTES_BODY_LIMIT = 512 * 1024
export const NOTE_TOMBSTONE_TTL_MS = 90 * 24 * 60 * 60 * 1000
/** Obergrenze der Grabsteine je Konto (die ältesten fallen früher weg, der Horizont rückt mit). */
export const MAX_NOTE_TOMBSTONES = 5000
export const NOTES_PAGE_DEFAULT = 200
export const NOTES_PAGE_MAX = 500

const iso = (ms: number) => new Date(ms).toISOString()

export type NoteWorld = { type: 'server', address: string } | { type: 'world', id: string, name?: string }

export interface NoteView {
  id: string
  world: NoteWorld
  title: string
  text: string
  createdAt: string
  updatedAt: string
}

export interface NoteTombstone {
  id: string
  world: NoteWorld
  deleted: true
  updatedAt: string
}

export type NoteEntry = NoteView | NoteTombstone

interface NoteRow {
  id: string
  world_type: 'server' | 'world'
  world_ref: string
  world_name: string | null
  title: string
  text: string
  created_at: number
  updated_at: number
  deleted: number
  seq: number
}

function worldOf(r: NoteRow): NoteWorld {
  if (r.world_type === 'server') return { type: 'server', address: r.world_ref }
  return r.world_name ? { type: 'world', id: r.world_ref, name: r.world_name } : { type: 'world', id: r.world_ref }
}

function entryView(r: NoteRow): NoteEntry {
  if (r.deleted === 1) return { id: r.id, world: worldOf(r), deleted: true, updatedAt: iso(r.updated_at) }
  return { id: r.id, world: worldOf(r), title: r.title, text: r.text, createdAt: iso(r.created_at), updatedAt: iso(r.updated_at) }
}

// ---------------------------------------------------------------- Validierung

const codePoints = (s: string) => [...s].length
/** Steuerzeichen (C0, DEL, C1) und Zeilen-/Absatztrenner; `\n` nur, wenn `newline` erlaubt ist. */
function hasControl(s: string, newline: boolean): boolean {
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    if (c === 0x0a && newline) continue
    if (c < 0x20 || (c >= 0x7f && c <= 0x9f) || c === 0x2028 || c === 0x2029) return true
  }
  return false
}

/** Adresse ohne Leerraum, Schr\u00e4gstriche und Steuerzeichen. */
const plainAddress = (s: string) => !hasControl(s, false) && !/[\s/\\]/.test(s)

const line = (max: number) =>
  z.string().refine((s) => codePoints(s) <= max, `at most ${max} characters`).refine((s) => !hasControl(s, false), 'must not contain control characters')

export const noteIdSchema = z.string().regex(/^[0-9a-f]{16}$/, 'must be 16 lowercase hex digits')

const noteWorld = z.discriminatedUnion('type', [
  z.strictObject({
    type: z.literal('server'),
    address: z
      .string()
      .min(1)
      .max(MAX_NOTE_ADDRESS)
      .refine(plainAddress, 'must be a plain host[:port] without spaces or slashes')
      .transform((s) => s.toLowerCase()),
  }),
  z.strictObject({
    type: z.literal('world'),
    id: z.string().regex(/^[0-9a-fA-F]{16}$/, 'must be 16 hex digits').transform((s) => s.toLowerCase()),
    name: line(MAX_NOTE_WORLD_NAME).optional(),
  }),
])

const isoTime = z.iso.datetime({ offset: true })

const noteChange = z.union([
  z.strictObject({
    id: noteIdSchema,
    world: noteWorld,
    deleted: z.literal(true),
    updatedAt: isoTime,
  }),
  z.strictObject({
    id: noteIdSchema,
    world: noteWorld,
    title: line(MAX_NOTE_TITLE),
    text: z
      .string()
      .refine((s) => codePoints(s) <= MAX_NOTE_TEXT, `at most ${MAX_NOTE_TEXT} characters`)
      .refine((s) => !hasControl(s, true), 'must not contain control characters except \\n'),
    createdAt: isoTime,
    updatedAt: isoTime,
  }),
])

type NoteChange = z.output<typeof noteChange>

/** Hülle: 1–50 Einträge, jeder mit gültiger `id` (für die Zuordnung der Ergebnisse); der Rest wird je Eintrag geprüft. */
export const notesPostBody = z.strictObject({
  changes: z.array(z.looseObject({ id: noteIdSchema })).min(1).max(50),
})

export const notesQuery = z.strictObject({
  since: z.string().regex(/^n1\.\d{1,15}$/, 'must be a cursor returned by this endpoint').optional(),
  limit: z.coerce.number().int().min(1).max(NOTES_PAGE_MAX).default(NOTES_PAGE_DEFAULT),
})

// ---------------------------------------------------------------- Lesen

const cursorOf = (seq: number) => `n1.${seq}`

function state(ctx: AppContext, uuid: string): { seq: number, horizon: number } {
  return one<{ seq: number, horizon: number }>(ctx.db, 'SELECT seq, horizon FROM sync_note_state WHERE uuid = ?', uuid) ?? { seq: 0, horizon: 0 }
}

export interface NotesPage {
  notes: NoteEntry[]
  cursor: string
  more: boolean
  /** Komplette Liste statt Änderungen (Cursor zu alt oder unbekannt): lokal synchronisierte Notizen, die fehlen, verwerfen. */
  reset: boolean
}

/** `GET /v1/me/sync/notes`: Änderungen nach `since` (ohne = alles), nach Änderungsnummer sortiert. */
export function listNotes(ctx: AppContext, uuid: string, q: z.output<typeof notesQuery>): NotesPage {
  const st = state(ctx, uuid)
  let since = q.since ? Number(q.since.slice(3)) : 0
  let reset = false
  // Cursor vor dem Horizont (Grabsteine schon weg) oder aus der Zukunft (fremd/zurückgesetzt) → alles neu.
  if (q.since && (since < st.horizon || since > st.seq)) {
    since = 0
    reset = true
  }
  // Grabsteine immer mit (auch ohne Cursor): ein Gerät mit alter lokaler Kopie übernimmt so die Löschung.
  const rows = all<NoteRow>(
    ctx.db,
    'SELECT * FROM sync_notes WHERE uuid = ? AND seq > ? ORDER BY seq LIMIT ?',
    uuid, since, q.limit + 1,
  )
  const more = rows.length > q.limit
  const page = more ? rows.slice(0, q.limit) : rows
  const cursor = more ? page[page.length - 1]!.seq : st.seq
  return { notes: page.map(entryView), cursor: cursorOf(cursor), more, reset }
}

// ---------------------------------------------------------------- Schreiben

export type NoteResult =
  | { id: string, status: 'ok' }
  | { id: string, status: 'stale', current: NoteEntry }
  | { id: string, status: 'note_limit' }
  | { id: string, status: 'invalid' }

function worldKey(w: NoteWorld): { type: 'server' | 'world', ref: string, name: string | null } {
  return w.type === 'server' ? { type: 'server', ref: w.address, name: null } : { type: 'world', ref: w.id, name: w.name ?? null }
}

function applyChange(ctx: AppContext, uuid: string, c: NoteChange): NoteResult {
  const now = ctx.now()
  const updatedAt = Date.parse(c.updatedAt)
  if (!Number.isFinite(updatedAt) || updatedAt > now + MAX_CLOCK_SKEW_MS || updatedAt <= 0) return { id: c.id, status: 'invalid' }
  const deleted = 'deleted' in c
  let createdAt = deleted ? updatedAt : Date.parse(c.createdAt)
  if (!Number.isFinite(createdAt) || createdAt <= 0) return { id: c.id, status: 'invalid' }
  createdAt = Math.min(createdAt, updatedAt)

  const stored = one<NoteRow>(ctx.db, 'SELECT * FROM sync_notes WHERE uuid = ? AND id = ?', uuid, c.id)
  if (stored && stored.updated_at > updatedAt) return { id: c.id, status: 'stale', current: entryView(stored) }
  const w = worldKey(c.world)

  if (!deleted) {
    // Grenzen nur für neue/wiederbelebte Notizen bzw. beim Umzug in eine andere Welt.
    const live = stored && stored.deleted === 0
    const sameWorld = live && stored.world_type === w.type && stored.world_ref === w.ref
    if (!live) {
      const n = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM sync_notes WHERE uuid = ? AND deleted = 0', uuid)!.n
      if (n >= MAX_NOTES_PER_ACCOUNT) return { id: c.id, status: 'note_limit' }
    }
    if (!sameWorld) {
      const n = one<{ n: number }>(
        ctx.db,
        'SELECT COUNT(*) AS n FROM sync_notes WHERE uuid = ? AND deleted = 0 AND world_type = ? AND world_ref = ?',
        uuid, w.type, w.ref,
      )!.n
      if (n >= MAX_NOTES_PER_WORLD) return { id: c.id, status: 'note_limit' }
    }
  }

  run(ctx.db, 'INSERT INTO sync_note_state (uuid, seq) VALUES (?, 1) ON CONFLICT(uuid) DO UPDATE SET seq = seq + 1', uuid)
  const seq = state(ctx, uuid).seq
  run(
    ctx.db,
    `INSERT INTO sync_notes (uuid, id, world_type, world_ref, world_name, title, text, created_at, updated_at, deleted, deleted_at, seq)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(uuid, id) DO UPDATE SET world_type = excluded.world_type, world_ref = excluded.world_ref,
       world_name = excluded.world_name, title = excluded.title, text = excluded.text, created_at = excluded.created_at,
       updated_at = excluded.updated_at, deleted = excluded.deleted, deleted_at = excluded.deleted_at, seq = excluded.seq`,
    uuid, c.id, w.type, w.ref, w.name,
    deleted ? '' : c.title, deleted ? '' : c.text,
    // Grabstein behält die Erstellzeit der Notiz (falls bekannt).
    deleted ? (stored?.created_at ?? createdAt) : createdAt, updatedAt, deleted ? 1 : 0, deleted ? now : null, seq,
  )
  return { id: c.id, status: 'ok' }
}

/** Zu viele Grabsteine: die ältesten entfernen und den Horizont nachziehen. */
function capTombstones(ctx: AppContext, uuid: string): void {
  const n = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM sync_notes WHERE uuid = ? AND deleted = 1', uuid)!.n
  if (n <= MAX_NOTE_TOMBSTONES) return
  const cut = one<{ seq: number }>(
    ctx.db,
    'SELECT seq FROM sync_notes WHERE uuid = ? AND deleted = 1 ORDER BY seq LIMIT 1 OFFSET ?',
    uuid, n - MAX_NOTE_TOMBSTONES - 1,
  )!.seq
  run(ctx.db, 'UPDATE sync_note_state SET horizon = MAX(horizon, ?) WHERE uuid = ?', cut, uuid)
  run(ctx.db, 'DELETE FROM sync_notes WHERE uuid = ? AND deleted = 1 AND seq <= ?', uuid, cut)
}

/** `POST /v1/me/sync/notes`: Ergebnisse in derselben Reihenfolge. `changed` = mindestens eine Änderung gespeichert. */
export function pushNotes(ctx: AppContext, uuid: string, changes: readonly unknown[]): { results: NoteResult[], changed: boolean } {
  return tx(ctx.db, () => {
    const results: NoteResult[] = []
    for (const raw of changes) {
      const id = (raw as { id: string }).id
      const parsed = noteChange.safeParse(raw)
      results.push(parsed.success ? applyChange(ctx, uuid, parsed.data) : { id, status: 'invalid' })
    }
    const changed = results.some((r) => r.status === 'ok')
    if (changed) capTombstones(ctx, uuid)
    return { results, changed }
  })
}

/** Aktueller Cursor (für das Ereignis `notes_changed`). */
export function notesCursor(ctx: AppContext, uuid: string): string {
  return cursorOf(state(ctx, uuid).seq)
}

/** Grabsteine älter als 90 Tage (Serverzeit) entfernen, Horizont je Konto nachziehen – läuft mit `sweepExpired`. */
export function sweepNoteTombstones(ctx: AppContext): number {
  const cutoff = ctx.now() - NOTE_TOMBSTONE_TTL_MS
  return tx(ctx.db, () => {
    run(
      ctx.db,
      `UPDATE sync_note_state SET horizon = MAX(horizon, COALESCE((
         SELECT MAX(seq) FROM sync_notes n WHERE n.uuid = sync_note_state.uuid AND n.deleted = 1 AND n.deleted_at <= ?), 0))
       WHERE uuid IN (SELECT uuid FROM sync_notes WHERE deleted = 1 AND deleted_at <= ?)`,
      cutoff, cutoff,
    )
    return run(ctx.db, 'DELETE FROM sync_notes WHERE deleted = 1 AND deleted_at <= ?', cutoff)
  })
}
