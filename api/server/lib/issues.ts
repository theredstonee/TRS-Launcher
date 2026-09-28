import { randomBytes } from 'node:crypto'
import { isIPv6 } from 'node:net'
import { readdirSync, readFileSync, rmSync, statSync } from 'node:fs'
import { join } from 'node:path'
import {
  CLOSED_STATUSES,
  ISSUE_LIMITS,
  ISSUE_TAG,
  UPLOAD_ID,
  isClosed,
  type IssueArea,
  type IssueChange,
  type IssueCommentView,
  type IssueDetail,
  type IssueHistoryAction,
  type IssueHistoryEntry,
  type IssueListResult,
  type IssueMetaView,
  type IssueNoteView,
  type IssuePriority,
  type IssueSort,
  type IssueStatus,
  type IssueType,
  type IssueUpdatedEvent,
  type IssueView,
  type PlayerRefView,
  type RoadmapResult,
  type UploadView,
} from '../../shared/issues'
import { writeFileAtomic } from './attachments'
import { audit } from './audit'
import type { AppContext } from './context'
import { all, one, placeholders, run, tx, type Param } from './db'
import { ApiError, badRequest, conflict, forbidden, notFound } from './errors'
import { sha256Hex } from './ids'
import { processImage, type ImageLimits, type OutputMime } from './images'
import { applyWordFilter, sanitizeText, textLength } from './safety'
import { assertNotSanctioned, nameMap } from './sanctions'
import { can, teamOf, type Staff } from './team'
import { getUser } from './users'

export type { IssueUpdatedEvent } from '../../shared/issues'

/**
 * Issues & Roadmap (§28): öffentlicher Issue-Tracker. Spieler melden Fehler und wünschen Funktionen für Launcher,
 * TRS Client oder Website, stimmen hoch/runter (je Konto eine Stimme, änderbar), kommentieren und folgen. Das Team
 * sortiert (Status, Priorität, Zuständig, Tags, „Erledigt in“), führt Duplikate zusammen und schreibt interne Notizen.
 *
 * Lesen ist öffentlich, Schreiben braucht ein Konto (Website-Sitzung oder Bearer-Token). Beschreibungen und
 * Kommentare sind Markdown – gerendert wird bei den Clients ohne rohes HTML (§28.2). Bilder werden immer neu kodiert
 * und liegen unverschlüsselt unter `<DATA_DIR>/issues/<xx>/<id>.<jpg|png>` (Inhalt ist ohnehin öffentlich).
 */

const iso = (t: number) => new Date(t).toISOString()
const isoOrNull = (t: number | null) => (t === null ? null : iso(t))
const DAY = 24 * 60 * 60 * 1000
const UPLOAD_TTL_MS = 60 * 60 * 1000
const DELETED_RETENTION_MS = 90 * DAY

export const ISSUE_IMAGE_LIMITS: ImageLimits = {
  maxBytes: ISSUE_LIMITS.uploadMaxBytes,
  maxOutputEdge: 2560,
  quality: 88,
  thumbEdge: 400,
  thumbQuality: 75,
}

/** Gleichzeitig laufende Neukodierungen (große Bilder blockieren den Event-Loop kurz). */
const MAX_PARALLEL = 2
let running = 0

// ---------------------------------------------------------------- Zeilen

export interface IssueRow {
  id: number
  type: IssueType
  area: IssueArea
  status: IssueStatus
  title: string
  description: string
  author_uuid: string | null
  author_team: number
  source: 'web' | 'client'
  meta: string | null
  log: string | null
  priority: IssuePriority | null
  assignee_uuid: string | null
  fixed_in: string | null
  duplicate_of: number | null
  locked: number
  up: number
  down: number
  score: number
  comments: number
  created_at: number
  updated_at: number
  edited_at: number | null
  activity_at: number
  closed_at: number | null
  deleted_at: number | null
  deleted_by: string | null
  deleted_reason: string | null
}

export interface CommentRow {
  id: number
  issue_id: number
  author_uuid: string | null
  team: number
  body: string | null
  created_at: number
  edited_at: number | null
  deleted_at: number | null
  deleted_by: 'author' | 'team' | null
}

export interface UploadRow {
  id: string
  owner_uuid: string | null
  issue_id: number | null
  comment_id: number | null
  sort: number
  mime: OutputMime
  width: number
  height: number
  bytes: number
  thumb_mime: OutputMime
  thumb_width: number
  thumb_height: number
  thumb_bytes: number
  sha256: string
  created_at: number
}

interface StoredMeta {
  modVersion?: string
  mcVersion?: string
  loader?: string
  mods?: string[]
}

/** Wer liest bzw. handelt (für `myVote`, `following`, Rechte). `null` = nicht angemeldet. */
export interface Viewer {
  uuid: string
  name: string
  staff: Staff | null
}

export const canManage = (v: Viewer | null): boolean => !!v?.staff && can(v.staff, 'issues.manage')
export const canModerate = (v: Viewer | null): boolean => !!v?.staff && can(v.staff, 'issues.moderate')
const isStaffViewer = (v: Viewer | null): boolean => canManage(v) || canModerate(v)

// ---------------------------------------------------------------- Text

/** Titel: eine Zeile, gesäubert, Wortfilter. */
export function cleanTitle(ctx: AppContext, raw: string): string {
  const t = sanitizeText(raw).replace(/\s+/g, ' ').trim()
  const n = textLength(t)
  if (n < ISSUE_LIMITS.titleMin || n > ISSUE_LIMITS.titleMax) {
    throw badRequest('invalid_request', `The title needs ${ISSUE_LIMITS.titleMin}–${ISSUE_LIMITS.titleMax} characters`, {
      fields: [{ path: 'title', message: `${ISSUE_LIMITS.titleMin}–${ISSUE_LIMITS.titleMax} characters` }],
    })
  }
  return applyWordFilter(ctx, t)
}

/** Markdown-Text (Beschreibung, Kommentar): gesäubert (Zeilen bleiben), Längen, Wortfilter. */
export function cleanBody(ctx: AppContext, raw: string, min: number, max: number, field: string): string {
  // sanitizeText zieht Leerzeilen zusammen – für Markdown (Code-Blöcke, Absätze) reicht das.
  const t = sanitizeText(raw)
  const n = textLength(t)
  if (n < min || n > max) {
    throw badRequest('invalid_request', `${field} needs ${min}–${max} characters`, { fields: [{ path: field, message: `${min}–${max} characters` }] })
  }
  return t === '' ? t : applyWordFilter(ctx, t)
}

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * Log-Ausschnitt vor dem Speichern säubern (Verteidigung in der Tiefe – der Client säubert schon selbst): Tokens,
 * Sitzungs-IDs, Zugangsdaten in Befehlszeilen, E-Mail-Adressen, IP-Adressen, UUIDs, Benutzerordner und die
 * übergebenen Namen (der Ersteller) werden ersetzt.
 */
export function scrubLog(raw: string, names: string[] = []): string {
  let s = raw.normalize('NFC').replace(/\r\n?/g, '\n').replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, '')
  // Minecraft-Sitzung „token:<accessToken>:<uuid>“ und Befehlszeilen-/Konfigurationswerte.
  s = s.replace(/token:[^\s:]{8,}:[0-9a-fA-F-]{32,36}/g, '<session>')
  s = s.replace(
    /(--?(?:accessToken|access[_-]?token|session|sessionId|uuid|xuid|clientId|userProperties|password|token)|\b(?:accessToken|access_token|refresh_token|refreshToken|sessionId|session_id|password|secret|apiKey|api_key|authorization))(["']?\s*[:=]\s*|\s+)("[^"\n]*"|'[^'\n]*'|[^\s,;}\]]+)/gi,
    (_m, key: string, sep: string) => `${key}${sep}<redacted>`,
  )
  s = s.replace(/\bBearer\s+[A-Za-z0-9._~+/=-]{8,}/gi, 'Bearer <redacted>')
  // JWTs (Minecraft-/Xbox-Tokens) und TRS-Tokens.
  s = s.replace(/\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}/g, '<token>')
  s = s.replace(/\btrs_[A-Za-z0-9_-]{10,}/g, '<token>')
  // E-Mail-Adressen.
  s = s.replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, '<email>')
  // UUIDs (mit und ohne Striche).
  s = s.replace(/\b[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}\b/g, '<uuid>')
  // IPv4 (jede Zahl ≤ 255, optional mit Port) und IPv6 (per isIPv6 geprüft, damit Uhrzeiten bleiben).
  s = s.replace(/\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b/g, '<ip>')
  s = s.replace(/(?<![\w:])[0-9a-fA-F]{0,4}(?::[0-9a-fA-F]{0,4}){2,7}(?![\w:])/g, (m) => (isIPv6(m) ? '<ip>' : m))
  // Benutzerordner (Windows, Linux, macOS).
  s = s.replace(/([A-Za-z]:[\\/]+Users[\\/]+)[^\\/\s"']+/gi, '$1<user>')
  s = s.replace(/(\/(?:home|Users)\/)[^/\s"']+/g, '$1<user>')
  for (const name of names) {
    if (name.length < 3) continue
    s = s.replace(new RegExp(`(?<![A-Za-z0-9_])${escapeRe(name)}(?![A-Za-z0-9_])`, 'gi'), '<player>')
  }
  return [...s].slice(0, ISSUE_LIMITS.logMax).join('')
}

const META_TEXT = /^[\p{L}\p{N} ._+()/,:-]*$/u

function cleanMetaField(v: string | undefined): string | undefined {
  if (v === undefined) return undefined
  const t = sanitizeText(v).replace(/\s+/g, ' ').trim()
  if (t === '') return undefined
  if (!META_TEXT.test(t)) throw badRequest('invalid_request', 'Invalid technical info', { fields: [{ path: 'meta', message: 'invalid characters' }] })
  return t
}

// ---------------------------------------------------------------- Sichten

export function issueUrl(ctx: AppContext, number: number): string {
  return `${ctx.config.siteUrl}/issues/${number}`
}

function uploadView(ctx: AppContext, r: Pick<UploadRow, 'id' | 'width' | 'height'>): UploadView {
  const url = `${ctx.config.siteUrl}/v1/issues/uploads/${r.id}`
  return { id: r.id, url, thumbUrl: `${url}?thumb=1`, width: r.width, height: r.height }
}

interface Person {
  name: string
  skin: string | null
}

/** Namen + zuletzt gesehene Skin-Adresse (für Köpfe ohne Mojang-Abfrage) vieler UUIDs auf einmal. */
function people(ctx: AppContext, uuids: Iterable<string | null>): Map<string, Person> {
  const list = [...new Set([...uuids].filter((u): u is string => !!u && u.length === 32))]
  const out = new Map<string, Person>()
  for (let i = 0; i < list.length; i += 400) {
    const part = list.slice(i, i + 400)
    for (const r of all<{ uuid: string, name: string, skin_url: string | null }>(ctx.db, `SELECT uuid, name, skin_url FROM users WHERE uuid IN (${placeholders(part.length)})`, ...part)) {
      out.set(r.uuid, { name: r.name, skin: r.skin_url })
    }
  }
  return out
}

function ref(names: Map<string, Person>, uuid: string | null): PlayerRefView | null {
  if (!uuid) return null
  const p = names.get(uuid)
  return { uuid, name: p?.name ?? '', skin: p?.skin ?? null }
}

/** Viele Issues auf einmal in Listen-Sichten (Namen, Tags, Duplikat-Ziele, eigene Stimme in wenigen Abfragen). */
export function issueViews(ctx: AppContext, rows: IssueRow[], viewer: Viewer | null, opts: { deleted?: boolean } = {}): IssueView[] {
  if (rows.length === 0) return []
  const ids = rows.map((r) => r.id)
  const names = people(ctx, rows.flatMap((r) => [r.author_uuid, r.assignee_uuid]))
  const tags = new Map<number, string[]>()
  for (const t of all<{ issue_id: number, tag: string }>(ctx.db, `SELECT issue_id, tag FROM issue_tags WHERE issue_id IN (${placeholders(ids.length)}) ORDER BY tag`, ...ids)) {
    const list = tags.get(t.issue_id) ?? []
    list.push(t.tag)
    tags.set(t.issue_id, list)
  }
  const dupIds = [...new Set(rows.map((r) => r.duplicate_of).filter((x): x is number => x !== null))]
  const dups = new Map<number, { id: number, title: string, status: IssueStatus }>()
  if (dupIds.length) {
    for (const d of all<{ id: number, title: string, status: IssueStatus }>(ctx.db, `SELECT id, title, status FROM issues WHERE id IN (${placeholders(dupIds.length)}) AND deleted_at IS NULL`, ...dupIds)) dups.set(d.id, d)
  }
  const votes = new Map<number, number>()
  if (viewer) {
    for (const v of all<{ issue_id: number, vote: number }>(ctx.db, `SELECT issue_id, vote FROM issue_votes WHERE uuid = ? AND issue_id IN (${placeholders(ids.length)})`, viewer.uuid, ...ids)) votes.set(v.issue_id, v.vote)
  }
  return rows.map((r) => {
    const d = r.duplicate_of !== null ? dups.get(r.duplicate_of) : undefined
    const view: IssueView = {
      number: r.id,
      url: issueUrl(ctx, r.id),
      type: r.type,
      area: r.area,
      status: r.status,
      title: r.title,
      author: ref(names, r.author_uuid),
      authorTeam: r.author_team === 1,
      score: r.score,
      up: r.up,
      down: r.down,
      comments: r.comments,
      priority: r.priority,
      assignee: ref(names, r.assignee_uuid),
      tags: tags.get(r.id) ?? [],
      fixedIn: r.fixed_in,
      duplicateOf: d ? { number: d.id, title: d.title, status: d.status, url: issueUrl(ctx, d.id) } : null,
      locked: r.locked === 1,
      source: r.source,
      createdAt: iso(r.created_at),
      updatedAt: iso(r.updated_at),
      activityAt: iso(r.activity_at),
      closedAt: isoOrNull(r.closed_at),
    }
    if (viewer) view.myVote = (votes.get(r.id) ?? 0) as -1 | 0 | 1
    if (opts.deleted || r.deleted_at !== null) view.deleted = r.deleted_at !== null
    return view
  })
}

function metaView(r: IssueRow, showLog: boolean): IssueMetaView | null {
  if (r.meta === null && r.log === null) return null
  let m: StoredMeta = {}
  try {
    m = r.meta ? (JSON.parse(r.meta) as StoredMeta) : {}
  } catch {
    m = {}
  }
  const view: IssueMetaView = {
    modVersion: m.modVersion ?? null,
    mcVersion: m.mcVersion ?? null,
    loader: m.loader ?? null,
    mods: Array.isArray(m.mods) ? m.mods : [],
    hasLog: r.log !== null,
  }
  if (showLog && r.log !== null) view.log = r.log
  return view
}

function uploadsOf(ctx: AppContext, issueId: number): Map<number | null, UploadRow[]> {
  const out = new Map<number | null, UploadRow[]>()
  for (const u of all<UploadRow>(ctx.db, 'SELECT * FROM issue_uploads WHERE issue_id = ? ORDER BY sort, created_at', issueId)) {
    const list = out.get(u.comment_id) ?? []
    list.push(u)
    out.set(u.comment_id, list)
  }
  return out
}

function commentViews(ctx: AppContext, rows: CommentRow[], uploads: Map<number | null, UploadRow[]>, viewer: Viewer | null): IssueCommentView[] {
  const names = people(ctx, rows.map((c) => c.author_uuid))
  return rows.map((c) => {
    const deleted = c.deleted_at !== null
    return {
      id: c.id,
      author: ref(names, c.author_uuid),
      team: c.team === 1,
      body: deleted ? null : c.body,
      attachments: deleted ? [] : (uploads.get(c.id) ?? []).map((u) => uploadView(ctx, u)),
      createdAt: iso(c.created_at),
      editedAt: isoOrNull(c.edited_at),
      deleted,
      deletedBy: deleted ? (c.deleted_by ?? 'team') : null,
      mine: !!viewer && c.author_uuid === viewer.uuid,
    }
  })
}

function historyViews(ctx: AppContext, issueId: number): IssueHistoryEntry[] {
  const rows = all<{ at: number, actor: string | null, action: IssueHistoryAction, from_value: string | null, to_value: string | null }>(
    ctx.db, 'SELECT at, actor, action, from_value, to_value FROM issue_history WHERE issue_id = ? ORDER BY at, id LIMIT 500', issueId,
  )
  const names = people(ctx, rows.map((r) => r.actor))
  return rows.map((r) => ({ at: iso(r.at), actor: ref(names, r.actor), action: r.action, from: r.from_value, to: r.to_value }))
}

function notesOf(ctx: AppContext, issueId: number): IssueNoteView[] {
  const rows = all<{ id: number, at: number, actor: string, text: string }>(
    ctx.db, 'SELECT id, at, actor, text FROM issue_notes WHERE issue_id = ? ORDER BY at, id LIMIT 500', issueId,
  )
  const names = people(ctx, rows.map((r) => r.actor))
  return rows.map((r) => ({ id: r.id, at: iso(r.at), author: ref(names, r.actor), text: r.text }))
}

// ---------------------------------------------------------------- Lesen

/** Issue-Zeile; gelöschte nur mit `includeDeleted`. */
export function getIssue(ctx: AppContext, number: number, includeDeleted = false): IssueRow | undefined {
  if (!Number.isSafeInteger(number) || number < 1) return undefined
  const r = one<IssueRow>(ctx.db, 'SELECT * FROM issues WHERE id = ?', number)
  if (!r || (r.deleted_at !== null && !includeDeleted)) return undefined
  return r
}

function issueOr404(ctx: AppContext, number: number, includeDeleted = false): IssueRow {
  const r = getIssue(ctx, number, includeDeleted)
  if (!r) throw notFound('issue_not_found', 'Issue not found')
  return r
}

export function detailOf(ctx: AppContext, r: IssueRow, viewer: Viewer | null): IssueDetail {
  const base = issueViews(ctx, [r], viewer)[0]!
  const uploads = uploadsOf(ctx, r.id)
  const staff = isStaffViewer(viewer)
  const mine = !!viewer && r.author_uuid === viewer.uuid
  const deleted = r.deleted_at !== null
  const detail: IssueDetail = {
    ...base,
    description: r.description,
    attachments: (uploads.get(null) ?? []).map((u) => uploadView(ctx, u)),
    editedAt: isoOrNull(r.edited_at),
    meta: metaView(r, mine || staff),
    can: {
      edit: mine && r.status === 'open' && !deleted,
      comment: !!viewer && !deleted && (r.locked === 0 || staff),
      vote: !!viewer && !deleted && !isClosed(r.status),
      manage: canManage(viewer),
      moderate: canModerate(viewer),
    },
  }
  if (viewer) {
    detail.following = one(ctx.db, 'SELECT 1 AS x FROM issue_follows WHERE issue_id = ? AND uuid = ?', r.id, viewer.uuid) !== undefined
  }
  if (canManage(viewer)) detail.notes = notesOf(ctx, r.id)
  return detail
}

export function issuePage(ctx: AppContext, number: number, viewer: Viewer | null): { issue: IssueDetail, comments: IssueCommentView[], history: IssueHistoryEntry[] } {
  const r = issueOr404(ctx, number, isStaffViewer(viewer))
  const uploads = uploadsOf(ctx, r.id)
  const comments = all<CommentRow>(ctx.db, 'SELECT * FROM issue_comments WHERE issue_id = ? ORDER BY id LIMIT 1000', r.id)
  return { issue: detailOf(ctx, r, viewer), comments: commentViews(ctx, comments, uploads, viewer), history: historyViews(ctx, r.id) }
}

export interface IssueListQuery {
  sort: IssueSort
  type?: IssueType
  area?: IssueArea
  status?: IssueStatus[]
  closed?: boolean
  q?: string
  page: number
  per: number
  /** Nur Team-Liste. */
  view?: 'all' | 'unassigned' | 'mine' | 'deleted'
  priority?: IssuePriority
}

const ORDER: Record<IssueSort, string> = {
  top: 'score DESC, activity_at DESC, id DESC',
  new: 'created_at DESC, id DESC',
  activity: 'activity_at DESC, id DESC',
}

function likeEscape(s: string): string {
  return s.replace(/[\\%_]/g, (c) => `\\${c}`)
}

export function listIssues(ctx: AppContext, q: IssueListQuery, viewer: Viewer | null): IssueListResult {
  const where: string[] = []
  const params: Param[] = []
  if (q.view === 'deleted') where.push('deleted_at IS NOT NULL')
  else where.push('deleted_at IS NULL')
  if (q.type) {
    where.push('type = ?')
    params.push(q.type)
  }
  if (q.area) {
    where.push('area = ?')
    params.push(q.area)
  }
  if (q.priority) {
    where.push('priority = ?')
    params.push(q.priority)
  }
  if (q.status && q.status.length) {
    where.push(`status IN (${placeholders(q.status.length)})`)
    params.push(...q.status)
  } else if (!q.closed && q.view !== 'deleted') {
    where.push(`status NOT IN (${placeholders(CLOSED_STATUSES.length)})`)
    params.push(...CLOSED_STATUSES)
  }
  if (q.view === 'unassigned') where.push('assignee_uuid IS NULL')
  if (q.view === 'mine' && viewer) {
    where.push('assignee_uuid = ?')
    params.push(viewer.uuid)
  }
  const needle = q.q?.trim()
  if (needle) {
    const num = /^#?(\d{1,9})$/.exec(needle)
    const like = `%${likeEscape(needle)}%`
    if (num) {
      where.push("(id = ? OR title LIKE ? ESCAPE '\\')")
      params.push(Number(num[1]), like)
    } else {
      where.push("(title LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\')")
      params.push(like, like)
    }
  }
  const cond = where.join(' AND ')
  const total = one<{ n: number }>(ctx.db, `SELECT COUNT(*) AS n FROM issues WHERE ${cond}`, ...params)!.n
  const per = q.per
  const pages = Math.max(1, Math.ceil(total / per))
  const page = Math.min(Math.max(1, q.page), pages)
  // ORDER stammt nur aus der festen Tabelle oben.
  const rows = all<IssueRow>(ctx.db, `SELECT * FROM issues WHERE ${cond} ORDER BY ${ORDER[q.sort]} LIMIT ? OFFSET ?`, ...params, per, (page - 1) * per)
  return { issues: issueViews(ctx, rows, viewer, { deleted: q.view === 'deleted' }), total, page, pages, per }
}

const PRIORITY_ORDER = "CASE priority WHEN 'critical' THEN 0 WHEN 'high' THEN 1 WHEN 'medium' THEN 2 WHEN 'low' THEN 3 ELSE 4 END"

export function roadmap(ctx: AppContext, viewer: Viewer | null): RoadmapResult {
  const max = ISSUE_LIMITS.roadmapMax
  const days = ISSUE_LIMITS.roadmapDoneDays
  const planned = all<IssueRow>(
    ctx.db, `SELECT * FROM issues WHERE deleted_at IS NULL AND status = 'planned' ORDER BY ${PRIORITY_ORDER}, score DESC, id DESC LIMIT ?`, max,
  )
  const progress = all<IssueRow>(
    ctx.db,
    `SELECT * FROM issues WHERE deleted_at IS NULL AND status IN ('in_progress', 'in_review')
     ORDER BY CASE status WHEN 'in_review' THEN 0 ELSE 1 END, ${PRIORITY_ORDER}, score DESC, id DESC LIMIT ?`,
    max,
  )
  const done = all<IssueRow>(
    ctx.db, "SELECT * FROM issues WHERE deleted_at IS NULL AND status = 'done' AND closed_at > ? ORDER BY closed_at DESC, id DESC LIMIT ?",
    ctx.now() - days * DAY, max,
  )
  return { planned: issueViews(ctx, planned, viewer), inProgress: issueViews(ctx, progress, viewer), done: issueViews(ctx, done, viewer), doneDays: days }
}

export function myIssues(ctx: AppContext, viewer: Viewer): { created: IssueView[], following: IssueView[] } {
  const created = all<IssueRow>(ctx.db, 'SELECT * FROM issues WHERE author_uuid = ? AND deleted_at IS NULL ORDER BY activity_at DESC LIMIT 100', viewer.uuid)
  const following = all<IssueRow>(
    ctx.db,
    `SELECT i.* FROM issue_follows f JOIN issues i ON i.id = f.issue_id
     WHERE f.uuid = ? AND i.deleted_at IS NULL ORDER BY i.activity_at DESC LIMIT 100`,
    viewer.uuid,
  )
  return { created: issueViews(ctx, created, viewer), following: issueViews(ctx, following, viewer) }
}

/** Zahlen für die Team-Übersicht: neue (offen, niemandem zugewiesen) und mir zugewiesene offene Issues. */
export function issueCounts(ctx: AppContext, uuid: string): { new: number, mine: number } {
  const closed = CLOSED_STATUSES.map((x) => `'${x}'`).join(', ')
  return {
    new: one<{ n: number }>(ctx.db, `SELECT COUNT(*) AS n FROM issues WHERE deleted_at IS NULL AND status = 'open' AND assignee_uuid IS NULL`)!.n,
    mine: one<{ n: number }>(ctx.db, `SELECT COUNT(*) AS n FROM issues WHERE deleted_at IS NULL AND assignee_uuid = ? AND status NOT IN (${closed})`, uuid)!.n,
  }
}

// ---------------------------------------------------------------- Grenzen

type ActionKind = 'issue' | 'comment' | 'upload'
const DAILY: Record<ActionKind, { max: number, code: string, what: string }> = {
  issue: { max: ISSUE_LIMITS.issuesPerDay, code: 'issue_daily_limit', what: 'issues' },
  comment: { max: ISSUE_LIMITS.commentsPerDay, code: 'comment_daily_limit', what: 'comments' },
  upload: { max: ISSUE_LIMITS.uploadsPerDay, code: 'upload_daily_limit', what: 'image uploads' },
}

function assertDaily(ctx: AppContext, uuid: string, kind: ActionKind): void {
  const d = DAILY[kind]
  const since = ctx.now() - DAY
  const n = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM issue_actions WHERE uuid = ? AND kind = ? AND at > ?', uuid, kind, since)!.n
  if (n < d.max) return
  const oldest = one<{ at: number }>(ctx.db, 'SELECT at FROM issue_actions WHERE uuid = ? AND kind = ? AND at > ? ORDER BY at LIMIT 1', uuid, kind, since)
  const retry = Math.max(1, Math.ceil(((oldest?.at ?? ctx.now()) + DAY - ctx.now()) / 1000))
  throw new ApiError(429, d.code, `At most ${d.max} ${d.what} per day`, { retryAfter: retry, max: d.max }, { 'Retry-After': String(retry) })
}

function logAction(ctx: AppContext, uuid: string, kind: ActionKind): void {
  run(ctx.db, 'INSERT INTO issue_actions (uuid, kind, at) VALUES (?, ?, ?)', uuid, kind, ctx.now())
}

// ---------------------------------------------------------------- Bilder

function ext(mime: OutputMime): string {
  return mime === 'image/png' ? 'png' : 'jpg'
}

function fileOf(ctx: AppContext, r: Pick<UploadRow, 'id' | 'mime' | 'thumb_mime'>, thumb: boolean): string {
  return join(ctx.issueDir, r.id.slice(0, 2), `${r.id}${thumb ? '.t' : ''}.${ext(thumb ? r.thumb_mime : r.mime)}`)
}

export function removeUploadFiles(ctx: AppContext, rows: Pick<UploadRow, 'id' | 'mime' | 'thumb_mime'>[]): void {
  for (const r of rows) {
    rmSync(fileOf(ctx, r, false), { force: true })
    rmSync(fileOf(ctx, r, true), { force: true })
  }
}

/** Bild prüfen (Typ ↔ Magic Bytes, Größe), neu kodieren, lose ablegen (1 h gültig bis zum Anhängen). */
export async function uploadIssueImage(ctx: AppContext, uuid: string, body: Buffer, contentType: string): Promise<UploadView> {
  assertNotSanctioned(ctx, uuid, 'upload_ban')
  assertDaily(ctx, uuid, 'upload')
  if (running >= MAX_PARALLEL) {
    throw new ApiError(503, 'busy', 'The server is busy processing images, try again in a few seconds', { retryAfter: 3 }, { 'Retry-After': '3' })
  }
  running++
  let img: Awaited<ReturnType<typeof processImage>>
  try {
    img = await processImage(body, contentType, ISSUE_IMAGE_LIMITS)
  } finally {
    running--
  }
  // Während des Neukodierens könnten parallele Uploads die Grenze erreicht haben.
  assertNotSanctioned(ctx, uuid, 'upload_ban')
  assertDaily(ctx, uuid, 'upload')
  const row: UploadRow = {
    id: randomBytes(16).toString('base64url'),
    owner_uuid: uuid,
    issue_id: null,
    comment_id: null,
    sort: 0,
    mime: img.full.mime,
    width: img.full.width,
    height: img.full.height,
    bytes: img.full.data.length,
    thumb_mime: img.thumb.mime,
    thumb_width: img.thumb.width,
    thumb_height: img.thumb.height,
    thumb_bytes: img.thumb.data.length,
    sha256: sha256Hex(img.full.data),
    created_at: ctx.now(),
  }
  writeFileAtomic(fileOf(ctx, row, false), img.full.data)
  writeFileAtomic(fileOf(ctx, row, true), img.thumb.data)
  try {
    tx(ctx.db, () => {
      run(
        ctx.db,
        `INSERT INTO issue_uploads (id, owner_uuid, issue_id, comment_id, sort, mime, width, height, bytes, thumb_mime, thumb_width, thumb_height,
           thumb_bytes, sha256, created_at) VALUES (?, ?, NULL, NULL, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        row.id, uuid, row.mime, row.width, row.height, row.bytes, row.thumb_mime, row.thumb_width, row.thumb_height, row.thumb_bytes,
        row.sha256, row.created_at,
      )
      logAction(ctx, uuid, 'upload')
    })
  } catch (err) {
    removeUploadFiles(ctx, [row])
    throw err
  }
  return uploadView(ctx, row)
}

/** Lose Uploads des Kontos an ein Issue bzw. einen Kommentar hängen (in der Transaktion des Aufrufers). */
function attachUploads(ctx: AppContext, uuid: string, ids: string[], issueId: number, commentId: number | null): void {
  const unique = [...new Set(ids)]
  const since = ctx.now() - UPLOAD_TTL_MS
  unique.forEach((id, i) => {
    const n = UPLOAD_ID.test(id)
      ? run(
          ctx.db,
          'UPDATE issue_uploads SET issue_id = ?, comment_id = ?, sort = ? WHERE id = ? AND owner_uuid = ? AND issue_id IS NULL AND created_at > ?',
          issueId, commentId, i, id, uuid, since,
        )
      : 0
    if (n !== 1) throw new ApiError(404, 'upload_not_found', 'An attached image was not found (expired or already used)', { id })
  })
}

/** Bild zum Ausliefern, wenn der Betrachter es sehen darf. */
export function readUpload(ctx: AppContext, id: string, viewer: Viewer | null, thumb: boolean): { row: UploadRow, data: Buffer, public: boolean } {
  const r = UPLOAD_ID.test(id) ? one<UploadRow>(ctx.db, 'SELECT * FROM issue_uploads WHERE id = ?', id) : undefined
  if (!r) throw notFound('upload_not_found', 'Image not found')
  let visible = false
  let pub = false
  if (r.issue_id === null) {
    visible = !!viewer && viewer.uuid === r.owner_uuid && r.created_at > ctx.now() - UPLOAD_TTL_MS
  } else {
    const issue = one<{ deleted_at: number | null }>(ctx.db, 'SELECT deleted_at FROM issues WHERE id = ?', r.issue_id)
    const comment = r.comment_id !== null ? one<{ deleted_at: number | null }>(ctx.db, 'SELECT deleted_at FROM issue_comments WHERE id = ?', r.comment_id) : null
    pub = !!issue && issue.deleted_at === null && (r.comment_id === null || (!!comment && comment.deleted_at === null))
    visible = pub || (!!issue && isStaffViewer(viewer))
  }
  if (!visible) throw notFound('upload_not_found', 'Image not found')
  return { row: r, data: readFileSync(fileOf(ctx, r, thumb)), public: pub }
}

// ---------------------------------------------------------------- Verlauf + Benachrichtigung

function history(ctx: AppContext, issueId: number, actor: string | null, action: IssueHistoryAction, from: string | null, to: string | null): void {
  run(ctx.db, 'INSERT INTO issue_history (issue_id, at, actor, action, from_value, to_value) VALUES (?, ?, ?, ?, ?, ?)', issueId, ctx.now(), actor, action, from, to)
}

function followers(ctx: AppContext, issueId: number): string[] {
  return all<{ uuid: string }>(ctx.db, 'SELECT uuid FROM issue_follows WHERE issue_id = ?', issueId).map((r) => r.uuid)
}

function follow(ctx: AppContext, issueId: number, uuid: string): void {
  run(ctx.db, 'INSERT OR IGNORE INTO issue_follows (issue_id, uuid, at) VALUES (?, ?, ?)', issueId, uuid, ctx.now())
}

/** `issue_updated` an alle Folgenden außer dem Handelnden (§28.6). */
function notify(
  ctx: AppContext,
  r: IssueRow,
  actor: { uuid: string, name: string } | null,
  change: IssueChange,
  extra: { mergedInto?: IssueRow, excerpt?: string, to?: string[] } = {},
): void {
  if (r.deleted_at !== null) return
  const e: IssueUpdatedEvent = {
    type: 'issue_updated',
    change,
    issue: { number: r.id, title: r.title, type: r.type, area: r.area, status: r.status, url: issueUrl(ctx, r.id) },
    by: actor && actor.uuid.length === 32 ? { uuid: actor.uuid, name: actor.name } : null,
    status: r.status,
    fixedIn: r.fixed_in,
    mergedInto: extra.mergedInto ? { number: extra.mergedInto.id, title: extra.mergedInto.title, url: issueUrl(ctx, extra.mergedInto.id) } : null,
    excerpt: extra.excerpt ?? null,
    at: iso(ctx.now()),
  }
  for (const u of extra.to ?? followers(ctx, r.id)) {
    if (actor && u === actor.uuid) continue
    ctx.events.publish(u, e, { meOnly: true })
  }
}

function excerpt(text: string): string {
  const flat = text.replace(/```[\s\S]*?```/g, ' ').replace(/[#>*_`~[\]()!]/g, '').replace(/\s+/g, ' ').trim()
  const chars = [...flat]
  return chars.length > 140 ? `${chars.slice(0, 139).join('')}…` : flat
}

function recount(ctx: AppContext, issueId: number): void {
  run(
    ctx.db,
    `UPDATE issues SET
       up = (SELECT COUNT(*) FROM issue_votes WHERE issue_id = ? AND vote = 1),
       down = (SELECT COUNT(*) FROM issue_votes WHERE issue_id = ? AND vote = -1),
       score = (SELECT COALESCE(SUM(vote), 0) FROM issue_votes WHERE issue_id = ?)
     WHERE id = ?`,
    issueId, issueId, issueId, issueId,
  )
}

function recountComments(ctx: AppContext, issueId: number): void {
  run(ctx.db, 'UPDATE issues SET comments = (SELECT COUNT(*) FROM issue_comments WHERE issue_id = ? AND deleted_at IS NULL) WHERE id = ?', issueId, issueId)
}

// ---------------------------------------------------------------- Spieler: anlegen, bearbeiten, voten, folgen

export interface IssueMetaInput {
  modVersion?: string
  mcVersion?: string
  loader?: string
  mods?: string[]
  log?: string
}

export interface CreateIssueInput {
  type: IssueType
  area: IssueArea
  title: string
  description: string
  attachments?: string[]
  meta?: IssueMetaInput
}

export function createIssue(ctx: AppContext, author: { uuid: string, name: string }, input: CreateIssueInput): IssueDetail {
  assertNotSanctioned(ctx, author.uuid, 'social_ban')
  assertDaily(ctx, author.uuid, 'issue')
  const title = cleanTitle(ctx, input.title)
  const description = cleanBody(ctx, input.description, 0, ISSUE_LIMITS.descriptionMax, 'description')
  let meta: string | null = null
  let log: string | null = null
  if (input.meta) {
    const m: StoredMeta = {}
    const mv = cleanMetaField(input.meta.modVersion)
    const mc = cleanMetaField(input.meta.mcVersion)
    const lo = cleanMetaField(input.meta.loader)
    if (mv) m.modVersion = mv
    if (mc) m.mcVersion = mc
    if (lo) m.loader = lo
    const mods = [...new Set((input.meta.mods ?? []).map((x) => sanitizeText(x).replace(/\s+/g, ' ').trim()).filter((x) => x !== ''))]
    if (mods.length) m.mods = mods.slice(0, ISSUE_LIMITS.modsMax)
    if (Object.keys(m).length) meta = JSON.stringify(m)
    const rawLog = input.meta.log?.trim()
    if (rawLog) log = scrubLog(rawLog, [author.name])
  }
  const staff = teamOf(ctx, author.uuid)
  const t = ctx.now()
  const source = input.meta?.modVersion ? 'client' : 'web'
  const id = tx(ctx.db, () => {
    const row = one<{ id: number }>(
      ctx.db,
      `INSERT INTO issues (type, area, status, title, description, author_uuid, author_team, source, meta, log, created_at, updated_at, activity_at)
       VALUES (?, ?, 'open', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id`,
      input.type, input.area, title, description, author.uuid, staff ? 1 : 0, source, meta, log, t, t, t,
    )!
    if (input.attachments?.length) attachUploads(ctx, author.uuid, input.attachments, row.id, null)
    follow(ctx, row.id, author.uuid)
    logAction(ctx, author.uuid, 'issue')
    return row.id
  })
  return detailOf(ctx, issueOr404(ctx, id), { uuid: author.uuid, name: author.name, staff })
}

export function editIssue(ctx: AppContext, viewer: Viewer, number: number, patch: { title?: string, description?: string, type?: IssueType }): IssueDetail {
  const r = issueOr404(ctx, number)
  if (r.author_uuid !== viewer.uuid) throw forbidden('forbidden', 'Only the author can edit this issue')
  if (r.status !== 'open') throw conflict('issue_not_editable', 'The team is already working on this issue – add a comment instead')
  assertNotSanctioned(ctx, viewer.uuid, 'social_ban')
  const title = patch.title !== undefined ? cleanTitle(ctx, patch.title) : r.title
  const description = patch.description !== undefined ? cleanBody(ctx, patch.description, 0, ISSUE_LIMITS.descriptionMax, 'description') : r.description
  const type = patch.type ?? r.type
  const t = ctx.now()
  run(ctx.db, 'UPDATE issues SET title = ?, description = ?, type = ?, edited_at = ?, updated_at = ? WHERE id = ?', title, description, type, t, t, r.id)
  return detailOf(ctx, issueOr404(ctx, r.id), viewer)
}

export function voteIssue(ctx: AppContext, viewer: Viewer, number: number, vote: -1 | 0 | 1): { score: number, up: number, down: number, myVote: -1 | 0 | 1 } {
  const r = issueOr404(ctx, number)
  if (isClosed(r.status)) throw conflict('issue_closed', 'Closed issues cannot be voted on')
  tx(ctx.db, () => {
    if (vote === 0) run(ctx.db, 'DELETE FROM issue_votes WHERE issue_id = ? AND uuid = ?', r.id, viewer.uuid)
    else {
      run(
        ctx.db,
        'INSERT INTO issue_votes (issue_id, uuid, vote, at) VALUES (?, ?, ?, ?) ON CONFLICT (issue_id, uuid) DO UPDATE SET vote = excluded.vote, at = excluded.at',
        r.id, viewer.uuid, vote, ctx.now(),
      )
    }
    recount(ctx, r.id)
  })
  const n = one<{ score: number, up: number, down: number }>(ctx.db, 'SELECT score, up, down FROM issues WHERE id = ?', r.id)!
  return { ...n, myVote: vote }
}

export function setFollow(ctx: AppContext, viewer: Viewer, number: number, on: boolean): void {
  const r = issueOr404(ctx, number)
  if (on) follow(ctx, r.id, viewer.uuid)
  else run(ctx.db, 'DELETE FROM issue_follows WHERE issue_id = ? AND uuid = ?', r.id, viewer.uuid)
}

// ---------------------------------------------------------------- Kommentare

function commentOr404(ctx: AppContext, issueId: number, id: number): CommentRow {
  const c = Number.isSafeInteger(id) ? one<CommentRow>(ctx.db, 'SELECT * FROM issue_comments WHERE id = ? AND issue_id = ?', id, issueId) : undefined
  if (!c) throw notFound('comment_not_found', 'Comment not found')
  return c
}

function commentView(ctx: AppContext, c: CommentRow, viewer: Viewer | null): IssueCommentView {
  const uploads = new Map<number | null, UploadRow[]>([[c.id, all<UploadRow>(ctx.db, 'SELECT * FROM issue_uploads WHERE comment_id = ? ORDER BY sort', c.id)]])
  return commentViews(ctx, [c], uploads, viewer)[0]!
}

export function addComment(ctx: AppContext, viewer: Viewer, number: number, input: { body: string, attachments?: string[] }): IssueCommentView {
  const r = issueOr404(ctx, number)
  const staff = isStaffViewer(viewer)
  if (r.locked === 1 && !staff) throw conflict('issue_locked', 'Comments on this issue are closed')
  assertNotSanctioned(ctx, viewer.uuid, 'social_ban')
  assertDaily(ctx, viewer.uuid, 'comment')
  const body = cleanBody(ctx, input.body, 1, ISSUE_LIMITS.commentMax, 'body')
  const team = teamOf(ctx, viewer.uuid) !== null
  const t = ctx.now()
  const id = tx(ctx.db, () => {
    const c = one<{ id: number }>(
      ctx.db, 'INSERT INTO issue_comments (issue_id, author_uuid, team, body, created_at) VALUES (?, ?, ?, ?, ?) RETURNING id',
      r.id, viewer.uuid, team ? 1 : 0, body, t,
    )!
    if (input.attachments?.length) attachUploads(ctx, viewer.uuid, input.attachments, r.id, c.id)
    run(ctx.db, 'UPDATE issues SET activity_at = ?, updated_at = ? WHERE id = ?', t, t, r.id)
    recountComments(ctx, r.id)
    // Wer kommentiert, folgt – vorher benachrichtigen wir die bisherigen Folgenden.
    logAction(ctx, viewer.uuid, 'comment')
    return c.id
  })
  if (team) notify(ctx, issueOr404(ctx, r.id), viewer, 'team_comment', { excerpt: excerpt(body) })
  follow(ctx, r.id, viewer.uuid)
  return commentView(ctx, commentOr404(ctx, r.id, id), viewer)
}

export function editComment(ctx: AppContext, viewer: Viewer, number: number, id: number, bodyRaw: string): IssueCommentView {
  const r = issueOr404(ctx, number)
  const c = commentOr404(ctx, r.id, id)
  if (c.author_uuid !== viewer.uuid || c.deleted_at !== null) throw notFound('comment_not_found', 'Comment not found')
  if (r.locked === 1 && !isStaffViewer(viewer)) throw conflict('issue_locked', 'Comments on this issue are closed')
  assertNotSanctioned(ctx, viewer.uuid, 'social_ban')
  const body = cleanBody(ctx, bodyRaw, 1, ISSUE_LIMITS.commentMax, 'body')
  run(ctx.db, 'UPDATE issue_comments SET body = ?, edited_at = ? WHERE id = ?', body, ctx.now(), c.id)
  return commentView(ctx, commentOr404(ctx, r.id, c.id), viewer)
}

function removeComment(ctx: AppContext, c: CommentRow, by: 'author' | 'team'): void {
  const files = all<UploadRow>(ctx.db, 'SELECT * FROM issue_uploads WHERE comment_id = ?', c.id)
  tx(ctx.db, () => {
    run(ctx.db, 'UPDATE issue_comments SET body = NULL, deleted_at = ?, deleted_by = ? WHERE id = ?', ctx.now(), by, c.id)
    run(ctx.db, 'DELETE FROM issue_uploads WHERE comment_id = ?', c.id)
    recountComments(ctx, c.issue_id)
  })
  removeUploadFiles(ctx, files)
}

export function deleteOwnComment(ctx: AppContext, viewer: Viewer, number: number, id: number): void {
  const r = issueOr404(ctx, number)
  const c = commentOr404(ctx, r.id, id)
  if (c.author_uuid !== viewer.uuid || c.deleted_at !== null) throw notFound('comment_not_found', 'Comment not found')
  removeComment(ctx, c, 'author')
}

// ---------------------------------------------------------------- Team

export interface AdminIssuePatch {
  status?: IssueStatus
  priority?: IssuePriority | null
  assignee?: string | null
  tags?: string[]
  fixedIn?: string | null
  type?: IssueType
  area?: IssueArea
  title?: string
  locked?: boolean
}

export function assigneeCandidates(ctx: AppContext): PlayerRefView[] {
  const uuids = new Set<string>(ctx.config.adminUuids)
  for (const m of all<{ uuid: string }>(ctx.db, 'SELECT DISTINCT uuid FROM team_members')) uuids.add(m.uuid)
  const out: PlayerRefView[] = []
  const names = nameMap(ctx, uuids)
  for (const u of uuids) {
    const staff = teamOf(ctx, u)
    if (staff && can(staff, 'issues.manage') && names.has(u)) out.push({ uuid: u, name: names.get(u)! })
  }
  return out.sort((a, b) => a.name.localeCompare(b.name))
}

export function adminUpdateIssue(ctx: AppContext, staff: Staff, number: number, patch: AdminIssuePatch): { issue: IssueDetail, history: IssueHistoryEntry[] } {
  const onlyLock = Object.keys(patch).every((k) => k === 'locked')
  if (!can(staff, 'issues.manage') && !(onlyLock && can(staff, 'issues.moderate'))) {
    throw new ApiError(403, 'missing_permission', 'Your role is missing the permission issues.manage', { permission: 'issues.manage' })
  }
  const r = issueOr404(ctx, number, true)
  if (r.deleted_at !== null) throw conflict('issue_deleted', 'Restore the issue first')
  if (patch.status === 'duplicate') throw badRequest('use_merge', 'Mark duplicates by merging them into the other issue')
  const t = ctx.now()
  const actor = staff.uuid
  const names = nameMap(ctx, [r.assignee_uuid, patch.assignee ?? null])
  let statusChanged = false
  let fixedChanged = false
  let tagsChanged = false
  const sets: string[] = []
  const params: Param[] = []
  const set = (col: string, v: Param) => {
    sets.push(`${col} = ?`)
    params.push(v)
  }

  let title = r.title
  if (patch.title !== undefined) {
    title = cleanTitle(ctx, patch.title)
    if (title !== r.title) set('title', title)
  }
  if (patch.assignee !== undefined && patch.assignee !== r.assignee_uuid) {
    if (patch.assignee !== null) {
      const s = teamOf(ctx, patch.assignee)
      if (!s || !can(s, 'issues.manage')) throw badRequest('invalid_assignee', 'This player cannot be assigned (needs issues.manage)')
    }
    set('assignee_uuid', patch.assignee)
  }
  let tags: string[] | null = null
  if (patch.tags !== undefined) {
    tags = [...new Set(patch.tags.map((x) => x.trim().toLowerCase()))]
    if (tags.length > ISSUE_LIMITS.tagsMax || tags.some((x) => !ISSUE_TAG.test(x))) throw badRequest('invalid_request', 'Invalid tags', { fields: [{ path: 'tags', message: 'invalid' }] })
  }

  tx(ctx.db, () => {
    if (patch.type !== undefined && patch.type !== r.type) {
      set('type', patch.type)
      history(ctx, r.id, actor, 'type', r.type, patch.type)
    }
    if (patch.area !== undefined && patch.area !== r.area) {
      set('area', patch.area)
      history(ctx, r.id, actor, 'area', r.area, patch.area)
    }
    if (title !== r.title) history(ctx, r.id, actor, 'title', r.title, title)
    if (patch.priority !== undefined && patch.priority !== r.priority) {
      set('priority', patch.priority)
      history(ctx, r.id, actor, 'priority', r.priority, patch.priority)
    }
    if (patch.assignee !== undefined && patch.assignee !== r.assignee_uuid) {
      const to = patch.assignee === null ? null : (names.get(patch.assignee) ?? getUser(ctx, patch.assignee)?.name ?? null)
      history(ctx, r.id, actor, 'assignee', r.assignee_uuid ? (names.get(r.assignee_uuid) ?? null) : null, to)
    }
    if (patch.fixedIn !== undefined && patch.fixedIn !== r.fixed_in) {
      set('fixed_in', patch.fixedIn)
      history(ctx, r.id, actor, 'fixed_in', r.fixed_in, patch.fixedIn)
      fixedChanged = patch.fixedIn !== null
    }
    if (patch.status !== undefined && patch.status !== r.status) {
      set('status', patch.status)
      const wasClosed = isClosed(r.status)
      const nowClosed = isClosed(patch.status)
      if (nowClosed && !wasClosed) set('closed_at', t)
      if (!nowClosed && wasClosed) {
        set('closed_at', null)
        set('duplicate_of', null)
      }
      history(ctx, r.id, actor, 'status', r.status, patch.status)
      statusChanged = true
    }
    if (patch.locked !== undefined && patch.locked !== (r.locked === 1)) {
      set('locked', patch.locked ? 1 : 0)
      history(ctx, r.id, actor, patch.locked ? 'locked' : 'unlocked', null, null)
    }
    if (tags) {
      const old = all<{ tag: string }>(ctx.db, 'SELECT tag FROM issue_tags WHERE issue_id = ? ORDER BY tag', r.id).map((x) => x.tag)
      const sorted = [...tags].sort()
      if (old.join(',') !== sorted.join(',')) {
        run(ctx.db, 'DELETE FROM issue_tags WHERE issue_id = ?', r.id)
        for (const tag of sorted) run(ctx.db, 'INSERT INTO issue_tags (issue_id, tag) VALUES (?, ?)', r.id, tag)
        history(ctx, r.id, actor, 'tags', old.join(', ') || null, sorted.join(', ') || null)
        tagsChanged = true
      }
    }
    if (sets.length || tagsChanged) {
      set('updated_at', t)
      if (statusChanged) set('activity_at', t)
      // Spaltennamen stammen nur aus diesem Code, Werte gehen als Parameter.
      run(ctx.db, `UPDATE issues SET ${sets.join(', ')} WHERE id = ?`, ...params, r.id)
      audit(ctx, actor, 'issue.update', r.author_uuid, JSON.stringify(patch).slice(0, 500), `i${r.id}`)
    }
  })
  const after = issueOr404(ctx, r.id)
  const who = { uuid: staff.uuid, name: getUser(ctx, staff.uuid)?.name ?? '' }
  if (fixedChanged) notify(ctx, after, who, 'fixed')
  else if (statusChanged) notify(ctx, after, who, 'status')
  const viewer: Viewer = { uuid: staff.uuid, name: who.name, staff }
  return { issue: detailOf(ctx, after, viewer), history: historyViews(ctx, r.id) }
}

/** Duplikat zusammenführen: Stimmen + Folgende wandern zum Ziel, die Quelle wird `duplicate` und verweist darauf. */
export function mergeIssue(ctx: AppContext, staff: Staff, number: number, into: number): IssueDetail {
  const src = issueOr404(ctx, number)
  const dst = getIssue(ctx, into)
  if (!dst || dst.id === src.id || dst.status === 'duplicate' || src.status === 'duplicate') {
    throw conflict('merge_invalid', 'These issues cannot be merged')
  }
  const told = followers(ctx, src.id)
  const t = ctx.now()
  tx(ctx.db, () => {
    run(ctx.db, 'INSERT OR IGNORE INTO issue_votes (issue_id, uuid, vote, at) SELECT ?, uuid, vote, at FROM issue_votes WHERE issue_id = ?', dst.id, src.id)
    run(ctx.db, 'DELETE FROM issue_votes WHERE issue_id = ?', src.id)
    run(ctx.db, 'INSERT OR IGNORE INTO issue_follows (issue_id, uuid, at) SELECT ?, uuid, at FROM issue_follows WHERE issue_id = ?', dst.id, src.id)
    run(ctx.db, 'DELETE FROM issue_follows WHERE issue_id = ?', src.id)
    run(
      ctx.db,
      "UPDATE issues SET status = 'duplicate', duplicate_of = ?, closed_at = COALESCE(closed_at, ?), locked = 1, activity_at = ?, updated_at = ? WHERE id = ?",
      dst.id, t, t, t, src.id,
    )
    run(ctx.db, 'UPDATE issues SET activity_at = ?, updated_at = ? WHERE id = ?', t, t, dst.id)
    recount(ctx, src.id)
    recount(ctx, dst.id)
    history(ctx, src.id, staff.uuid, 'merged_into', src.status, String(dst.id))
    history(ctx, dst.id, staff.uuid, 'merged_from', null, String(src.id))
    audit(ctx, staff.uuid, 'issue.merge', src.author_uuid, `#${src.id} -> #${dst.id}`, `i${src.id}`)
  })
  const who = { uuid: staff.uuid, name: getUser(ctx, staff.uuid)?.name ?? '' }
  notify(ctx, issueOr404(ctx, src.id), who, 'merged', { mergedInto: dst, to: told })
  return detailOf(ctx, issueOr404(ctx, dst.id), { uuid: staff.uuid, name: who.name, staff })
}

export function addNote(ctx: AppContext, staff: Staff, number: number, textRaw: string): IssueNoteView[] {
  const r = issueOr404(ctx, number, true)
  const text = sanitizeText(textRaw)
  if (textLength(text) < 1 || textLength(text) > ISSUE_LIMITS.noteMax) throw badRequest('invalid_request', `Notes need 1–${ISSUE_LIMITS.noteMax} characters`)
  run(ctx.db, 'INSERT INTO issue_notes (issue_id, at, actor, text) VALUES (?, ?, ?, ?)', r.id, ctx.now(), staff.uuid, text)
  audit(ctx, staff.uuid, 'issue.note', r.author_uuid, undefined, `i${r.id}`)
  return notesOf(ctx, r.id)
}

export function adminDeleteIssue(ctx: AppContext, staff: Staff, number: number, reason: string | null): void {
  const r = issueOr404(ctx, number, true)
  if (r.deleted_at !== null) return
  tx(ctx.db, () => {
    run(ctx.db, 'UPDATE issues SET deleted_at = ?, deleted_by = ?, deleted_reason = ? WHERE id = ?', ctx.now(), staff.uuid, reason, r.id)
    history(ctx, r.id, staff.uuid, 'deleted', null, null)
    audit(ctx, staff.uuid, 'issue.delete', r.author_uuid, reason ?? undefined, `i${r.id}`)
  })
}

export function adminRestoreIssue(ctx: AppContext, staff: Staff, number: number): IssueDetail {
  const r = issueOr404(ctx, number, true)
  if (r.deleted_at !== null) {
    tx(ctx.db, () => {
      run(ctx.db, 'UPDATE issues SET deleted_at = NULL, deleted_by = NULL, deleted_reason = NULL WHERE id = ?', r.id)
      history(ctx, r.id, staff.uuid, 'restored', null, null)
      audit(ctx, staff.uuid, 'issue.restore', r.author_uuid, undefined, `i${r.id}`)
    })
  }
  return detailOf(ctx, issueOr404(ctx, r.id), { uuid: staff.uuid, name: getUser(ctx, staff.uuid)?.name ?? '', staff })
}

export function adminDeleteComment(ctx: AppContext, staff: Staff, number: number, id: number): void {
  const r = issueOr404(ctx, number, true)
  const c = commentOr404(ctx, r.id, id)
  if (c.deleted_at !== null) return
  removeComment(ctx, c, 'team')
  audit(ctx, staff.uuid, 'issue.comment.delete', c.author_uuid, `#${r.id}/${c.id}`, `i${r.id}`)
}

// ---------------------------------------------------------------- Meldungen (§28.5)

export interface IssueEvidence {
  number: number
  title: string
  description: string
  author: PlayerRefView | null
}

export interface IssueCommentEvidence {
  id: number
  issueNumber: number
  issueTitle: string
  body: string
  author: PlayerRefView | null
}

export function issueForReport(ctx: AppContext, number: number): { row: IssueRow, evidence: IssueEvidence } {
  const r = issueOr404(ctx, number)
  const names = people(ctx, [r.author_uuid])
  return { row: r, evidence: { number: r.id, title: r.title, description: [...r.description].slice(0, 4000).join(''), author: ref(names, r.author_uuid) } }
}

export function commentForReport(ctx: AppContext, id: number): { row: CommentRow, evidence: IssueCommentEvidence } {
  const c = Number.isSafeInteger(id) ? one<CommentRow>(ctx.db, 'SELECT * FROM issue_comments WHERE id = ?', id) : undefined
  const r = c ? getIssue(ctx, c.issue_id) : undefined
  if (!c || !r || c.deleted_at !== null) throw notFound('comment_not_found', 'Comment not found')
  const names = people(ctx, [c.author_uuid])
  return { row: c, evidence: { id: c.id, issueNumber: r.id, issueTitle: r.title, body: c.body ?? '', author: ref(names, c.author_uuid) } }
}

/** Meldungs-Aktion `delete_issue`: den gemeldeten Kommentar bzw. das Issue löschen. */
export function deleteReported(ctx: AppContext, staff: Staff, issueId: number | null, commentId: number | null): void {
  if (commentId !== null) {
    const c = one<CommentRow>(ctx.db, 'SELECT * FROM issue_comments WHERE id = ?', commentId)
    if (c && c.deleted_at === null) {
      removeComment(ctx, c, 'team')
      audit(ctx, staff.uuid, 'issue.comment.delete', c.author_uuid, `#${c.issue_id}/${c.id}`, `i${c.issue_id}`)
    }
    return
  }
  if (issueId !== null && getIssue(ctx, issueId, true)) adminDeleteIssue(ctx, staff, issueId, 'report')
}

// ---------------------------------------------------------------- Kontolöschung + Aufräumen

/**
 * Kontolöschung (vor dem Löschen der Zeile aufrufen, in derselben Transaktion): Logs der eigenen Issues und alle
 * eigenen Bilder weg; Stimmen/Folgen per FK, Issues/Kommentare bleiben ohne Ersteller (FK SET NULL).
 * Rückgabe: Bilder, deren Dateien danach mit {@link removeUploadFiles} gelöscht werden.
 */
export function forgetIssueAuthor(ctx: AppContext, uuid: string): UploadRow[] {
  const files = all<UploadRow>(ctx.db, 'SELECT * FROM issue_uploads WHERE owner_uuid = ?', uuid)
  // Stimmen selbst löschen (statt per FK), damit die Zähler stimmen.
  const voted = all<{ issue_id: number }>(ctx.db, 'SELECT issue_id FROM issue_votes WHERE uuid = ?', uuid)
  run(ctx.db, 'DELETE FROM issue_votes WHERE uuid = ?', uuid)
  for (const v of voted) recount(ctx, v.issue_id)
  run(ctx.db, 'UPDATE issues SET log = NULL WHERE author_uuid = ?', uuid)
  run(ctx.db, 'DELETE FROM issue_uploads WHERE owner_uuid = ?', uuid)
  return files
}

/** Lose Uploads nach 1 h, gelöschte Issues nach 90 Tagen, Tagesprotokoll nach 24 h. */
export function sweepIssues(ctx: AppContext): void {
  const t = ctx.now()
  const loose = all<UploadRow>(ctx.db, 'SELECT * FROM issue_uploads WHERE issue_id IS NULL AND created_at <= ? LIMIT 500', t - UPLOAD_TTL_MS)
  if (loose.length) {
    run(ctx.db, `DELETE FROM issue_uploads WHERE id IN (${placeholders(loose.length)})`, ...loose.map((u) => u.id))
    removeUploadFiles(ctx, loose)
  }
  for (const r of all<{ id: number }>(ctx.db, 'SELECT id FROM issues WHERE deleted_at IS NOT NULL AND deleted_at <= ? LIMIT 200', t - DELETED_RETENTION_MS)) {
    const files = all<UploadRow>(ctx.db, 'SELECT * FROM issue_uploads WHERE issue_id = ?', r.id)
    tx(ctx.db, () => {
      run(ctx.db, 'UPDATE issues SET duplicate_of = NULL WHERE duplicate_of = ?', r.id)
      run(ctx.db, 'DELETE FROM issues WHERE id = ?', r.id)
    })
    removeUploadFiles(ctx, files)
  }
  run(ctx.db, 'DELETE FROM issue_actions WHERE at <= ?', t - DAY)
}

/** Dateien ohne Zeile (Absturz zwischen Schreiben und Eintragen), älter als eine Stunde. */
export function sweepOrphanIssueFiles(ctx: AppContext): number {
  let removed = 0
  const cutoff = Date.now() - 60 * 60_000
  let dirs: string[]
  try {
    dirs = readdirSync(ctx.issueDir)
  } catch {
    return 0
  }
  for (const d of dirs) {
    let files: string[]
    try {
      files = readdirSync(join(ctx.issueDir, d))
    } catch {
      continue
    }
    for (const f of files) {
      const m = /^([A-Za-z0-9_-]{22})(?:\.t)?\.(?:jpg|png)$/.exec(f)
      if (m && one(ctx.db, 'SELECT 1 AS x FROM issue_uploads WHERE id = ?', m[1]!) !== undefined) continue
      const full = join(ctx.issueDir, d, f)
      try {
        if (statSync(full).mtimeMs > cutoff) continue
        rmSync(full, { force: true })
        removed++
      } catch {
        // weiter
      }
    }
  }
  return removed
}
