import { audit } from './audit'
import type { AppContext } from './context'
import { all, one, placeholders, run, tx } from './db'
import { ApiError, badRequest, conflict, forbidden, notFound } from './errors'
import { broadcastPresence } from './friends'
import { endHostingFor } from './hosting'
import { emitCleared } from './playerevents'
import { getUser, staffRole, type StaffRole } from './users'

/**
 * Moderation v2 (API.md §22): eine Tabelle für alle Strafen, Rechte je Rolle, Änderungen mit Begründung,
 * Einsprüche, Durchsetzung in den Routen (`assertNotSanctioned`) und Ereignisse an den Spieler.
 */

export const SANCTION_KINDS = ['warn', 'chat_mute', 'social_ban', 'upload_ban', 'hosting_ban', 'account_ban'] as const
export type SanctionKind = (typeof SANCTION_KINDS)[number]

/** Grund-Vorlagen, die Moderatoren wählen können. Clients übersetzen sie. */
export const REASON_CODES = [
  'spam', 'insult_hate', 'harassment', 'inappropriate_content', 'inappropriate_name', 'scam_phishing',
  'impersonation', 'copyright', 'cheating', 'ban_evasion', 'other',
] as const
/** Dazu Codes, die nur das System vergibt: Automatik und übernommene Altdaten. */
export type ReasonCode = (typeof REASON_CODES)[number] | 'auto_spam' | 'auto_reports' | 'legacy'

/** Dauer-Vorlagen in Minuten (`permanent` = ohne Ende, `custom` = `minutes`). */
export const DURATIONS = { '1h': 60, '6h': 360, '1d': 1440, '3d': 4320, '7d': 10_080, '30d': 43_200 } as const
export type DurationPreset = keyof typeof DURATIONS | 'permanent' | 'custom'
/** Moderatoren: höchstens 7 Tage (Verwarnungen: 30 Tage), nie dauerhaft, nie Konto-Bann. */
export const MOD_MAX_MINUTES = 7 * 1440
export const MOD_MAX_WARN_MINUTES = 30 * 1440
/** Längste eigene Dauer: 10 Jahre (darüber: dauerhaft). */
export const MAX_CUSTOM_MINUTES = 3650 * 1440

export type SanctionStatus = 'active' | 'expired' | 'lifted'
export type AppealStatus = 'open' | 'lifted' | 'shortened' | 'upheld'

/** Wer handelt: Team-Mitglied (`uuid` oder `api-key`) oder `system` (Automatik). */
export interface Staff {
  uuid: string
  role: StaffRole
}
export const SYSTEM: Staff = { uuid: 'system', role: 'admin' }

/** Akteur-String (alte Aufrufer, Tests) → Staff. `api-key`/`system`/ADMIN_UUIDS = Admin. */
export function staffOf(ctx: AppContext, actor: string): Staff {
  if (actor === 'system') return SYSTEM
  if (actor === 'api-key') return { uuid: actor, role: 'admin' }
  return { uuid: actor, role: staffRole(ctx, actor) ?? 'moderator' }
}

export interface SanctionRow {
  id: number
  uuid: string
  kind: SanctionKind
  reason_code: ReasonCode
  reason: string | null
  note: string | null
  report_id: string | null
  auto: 'reports' | 'spam' | null
  created_at: number
  created_by: string
  created_role: StaffRole | 'system'
  expires_at: number | null
  lifted_at: number | null
  lifted_by: string | null
  lift_reason: string | null
  updated_at: number
  legacy_source: string | null
  legacy_id: number | null
}

interface ChangeRow {
  id: number
  sanction_id: number
  at: number
  actor: string
  action: 'shorten' | 'extend' | 'lift'
  old_expires_at: number | null
  new_expires_at: number | null
  reason: string
}

export interface AppealRow {
  id: number
  sanction_id: number
  uuid: string
  text: string
  status: AppealStatus
  created_at: number
  decided_at: number | null
  decided_by: string | null
  response: string | null
}

const iso = (t: number) => new Date(t).toISOString()
const isoOrNull = (t: number | null) => (t === null ? null : iso(t))

export function statusOf(s: Pick<SanctionRow, 'lifted_at' | 'expires_at'>, now: number): SanctionStatus {
  if (s.lifted_at !== null) return 'lifted'
  if (s.expires_at !== null && s.expires_at <= now) return 'expired'
  return 'active'
}

// ---------------------------------------------------------------- Lesen

export function getSanction(ctx: AppContext, id: number): SanctionRow | undefined {
  return one<SanctionRow>(ctx.db, 'SELECT * FROM sanctions WHERE id = ?', id)
}

function sanctionOr404(ctx: AppContext, id: number): SanctionRow {
  const s = getSanction(ctx, id)
  if (!s) throw notFound('sanction_not_found', 'Sanction not found')
  return s
}

/** Aktive Strafe dieser Art (die am längsten laufende), sonst `undefined`. */
export function activeSanction(ctx: AppContext, uuid: string, kind: SanctionKind): SanctionRow | undefined {
  return one<SanctionRow>(
    ctx.db,
    `SELECT * FROM sanctions WHERE uuid = ? AND kind = ? AND lifted_at IS NULL AND (expires_at IS NULL OR expires_at > ?)
     ORDER BY expires_at IS NULL DESC, expires_at DESC, id DESC LIMIT 1`,
    uuid, kind, ctx.now(),
  )
}

export function activeSanctions(ctx: AppContext, uuid: string): SanctionRow[] {
  return all<SanctionRow>(
    ctx.db,
    'SELECT * FROM sanctions WHERE uuid = ? AND lifted_at IS NULL AND (expires_at IS NULL OR expires_at > ?) ORDER BY created_at DESC, id DESC',
    uuid, ctx.now(),
  )
}

// ---------------------------------------------------------------- Ansichten

/** Was Clients über eine Strafe erfahren (Fehler `sanctioned`, Ereignisse, `/v1/me/sanctions`): nie Notiz oder Moderator. */
export interface MySanctionView {
  id: number
  kind: SanctionKind
  reasonCode: ReasonCode
  reason: string | null
  startsAt: string
  /** `null` = dauerhaft (bzw. automatische Stummschaltung bis zur Prüfung). */
  endsAt: string | null
  status: SanctionStatus
  liftedAt: string | null
  appeal: MyAppealView | null
  /** Einspruch möglich (aktiv, noch keiner eingelegt). */
  appealable: boolean
}

export interface MyAppealView {
  id: number
  status: AppealStatus
  createdAt: string
  decidedAt: string | null
  /** Antwort des Teams an den Spieler. */
  response: string | null
}

function appealOf(ctx: AppContext, sanctionId: number): AppealRow | undefined {
  return one<AppealRow>(ctx.db, 'SELECT * FROM sanction_appeals WHERE sanction_id = ?', sanctionId)
}

function myAppealView(a: AppealRow): MyAppealView {
  return { id: a.id, status: a.status, createdAt: iso(a.created_at), decidedAt: isoOrNull(a.decided_at), response: a.response }
}

export function mySanctionView(ctx: AppContext, s: SanctionRow): MySanctionView {
  const status = statusOf(s, ctx.now())
  const a = appealOf(ctx, s.id)
  return {
    id: s.id,
    kind: s.kind,
    reasonCode: s.reason_code,
    reason: s.reason,
    startsAt: iso(s.created_at),
    endsAt: isoOrNull(s.expires_at),
    status,
    liftedAt: isoOrNull(s.lifted_at),
    appeal: a ? myAppealView(a) : null,
    appealable: status === 'active' && !a,
  }
}

export interface ActorRef {
  uuid: string
  name: string | null
}

export interface SanctionChangeView {
  at: string
  actor: ActorRef
  action: 'shorten' | 'extend' | 'lift'
  oldEndsAt: string | null
  newEndsAt: string | null
  reason: string
}

export interface AdminAppealView extends MyAppealView {
  text: string
  decidedBy: ActorRef | null
}

export interface AdminSanctionView {
  id: number
  player: { uuid: string, name: string | null }
  kind: SanctionKind
  reasonCode: ReasonCode
  reason: string | null
  note: string | null
  reportId: string | null
  auto: 'reports' | 'spam' | null
  createdAt: string
  createdBy: ActorRef
  createdRole: StaffRole | 'system'
  endsAt: string | null
  permanent: boolean
  status: SanctionStatus
  liftedAt: string | null
  liftedBy: ActorRef | null
  liftReason: string | null
  changes: SanctionChangeView[]
  appeal: AdminAppealView | null
  migrated: boolean
}

/** Namen für viele UUIDs auf einmal (auch `api-key`/`system`, die bleiben ohne Namen). */
export function nameMap(ctx: AppContext, uuids: Iterable<string | null>): Map<string, string> {
  const list = [...new Set([...uuids].filter((u): u is string => !!u && u.length === 32))]
  const out = new Map<string, string>()
  for (let i = 0; i < list.length; i += 400) {
    const part = list.slice(i, i + 400)
    for (const r of all<{ uuid: string, name: string }>(ctx.db, `SELECT uuid, name FROM users WHERE uuid IN (${placeholders(part.length)})`, ...part)) {
      out.set(r.uuid, r.name)
    }
  }
  return out
}

export function adminSanctionViews(ctx: AppContext, rows: SanctionRow[]): AdminSanctionView[] {
  if (rows.length === 0) return []
  const ids = rows.map((r) => r.id)
  const changes = new Map<number, ChangeRow[]>()
  const appeals = new Map<number, AppealRow>()
  for (let i = 0; i < ids.length; i += 400) {
    const part = ids.slice(i, i + 400)
    for (const c of all<ChangeRow>(ctx.db, `SELECT * FROM sanction_changes WHERE sanction_id IN (${placeholders(part.length)}) ORDER BY id`, ...part)) {
      const list = changes.get(c.sanction_id) ?? []
      list.push(c)
      changes.set(c.sanction_id, list)
    }
    for (const a of all<AppealRow>(ctx.db, `SELECT * FROM sanction_appeals WHERE sanction_id IN (${placeholders(part.length)})`, ...part)) {
      appeals.set(a.sanction_id, a)
    }
  }
  const names = nameMap(ctx, [
    ...rows.flatMap((r) => [r.uuid, r.created_by, r.lifted_by]),
    ...[...changes.values()].flat().map((c) => c.actor),
    ...[...appeals.values()].map((a) => a.decided_by),
  ])
  const ref = (u: string): ActorRef => ({ uuid: u, name: names.get(u) ?? null })
  const t = ctx.now()
  return rows.map((s) => {
    const a = appeals.get(s.id)
    return {
      id: s.id,
      player: { uuid: s.uuid, name: names.get(s.uuid) ?? null },
      kind: s.kind,
      reasonCode: s.reason_code,
      reason: s.reason,
      note: s.note,
      reportId: s.report_id,
      auto: s.auto,
      createdAt: iso(s.created_at),
      createdBy: ref(s.created_by),
      createdRole: s.created_role,
      endsAt: isoOrNull(s.expires_at),
      permanent: s.expires_at === null,
      status: statusOf(s, t),
      liftedAt: isoOrNull(s.lifted_at),
      liftedBy: s.lifted_by ? ref(s.lifted_by) : null,
      liftReason: s.lift_reason,
      changes: (changes.get(s.id) ?? []).map((c) => ({
        at: iso(c.at),
        actor: ref(c.actor),
        action: c.action,
        oldEndsAt: isoOrNull(c.old_expires_at),
        newEndsAt: isoOrNull(c.new_expires_at),
        reason: c.reason,
      })),
      appeal: a ? { ...myAppealView(a), text: a.text, decidedBy: a.decided_by ? ref(a.decided_by) : null } : null,
      migrated: s.legacy_source !== null,
    }
  })
}

export function adminSanctionView(ctx: AppContext, s: SanctionRow): AdminSanctionView {
  return adminSanctionViews(ctx, [s])[0]!
}

// ---------------------------------------------------------------- Durchsetzung

/** Fehler an Clients: stabiler Code + öffentliche Angaben zur Strafe (ohne Notiz, ohne Moderator). */
export function sanctionError(ctx: AppContext, s: SanctionRow): ApiError {
  const v = mySanctionView(ctx, s)
  const details = { until: v.endsAt, sanction: v }
  if (s.kind === 'account_ban') return new ApiError(403, 'banned', 'This account is banned', details)
  if (s.kind === 'chat_mute') return new ApiError(403, 'chat_muted', 'You are muted in chat', details)
  return new ApiError(403, 'sanctioned', `This feature is blocked for your account (${s.kind})`, details)
}

/** Wirft `sanctioned` (bzw. `chat_muted`/`banned`), wenn eine Strafe dieser Art aktiv ist. */
export function assertNotSanctioned(ctx: AppContext, uuid: string, kind: Exclude<SanctionKind, 'warn'>): void {
  const s = activeSanction(ctx, uuid, kind)
  if (s) throw sanctionError(ctx, s)
}

// ---------------------------------------------------------------- Rechte

const AUDIT_ADD: Record<SanctionKind, string> = {
  warn: 'chat.warn',
  chat_mute: 'chat.mute',
  social_ban: 'sanction.social_ban',
  upload_ban: 'sanction.upload_ban',
  hosting_ban: 'sanction.hosting_ban',
  account_ban: 'user.ban',
}

function maxMinutesFor(kind: SanctionKind): number {
  return kind === 'warn' ? MOD_MAX_WARN_MINUTES : MOD_MAX_MINUTES
}

/** Darf `actor` gegen `target` überhaupt eine Strafe verhängen? */
export function assertCanTarget(ctx: AppContext, actor: Staff, target: string): void {
  const role = staffRole(ctx, target)
  if (role === 'admin') throw conflict('cannot_moderate_admin', 'Admins cannot be sanctioned; remove the role first')
  if (actor.uuid === target) throw badRequest('cannot_target_self', 'You cannot sanction yourself')
  if (role === 'moderator' && actor.role !== 'admin') throw forbidden('cannot_moderate_staff', 'Moderators cannot sanction team members')
}

/** Dauer und Art gegen die Rolle prüfen (`minutes = null` = dauerhaft). */
export function assertDurationAllowed(actor: Staff, kind: SanctionKind, minutes: number | null): void {
  if (actor.role === 'admin') return
  if (kind === 'account_ban') throw forbidden('admin_only', 'Only admins can ban accounts')
  if (minutes === null) throw forbidden('duration_not_allowed', 'Only admins can give permanent sanctions')
  if (minutes > maxMinutesFor(kind)) {
    throw forbidden('duration_not_allowed', `Moderators can give at most ${maxMinutesFor(kind) / 1440} days`)
  }
}

/** Darf `actor` diese bestehende Strafe ändern/aufheben? (Moderatoren: keine Strafen von Admins, keine Konto-Banns.) */
export function assertCanModify(actor: Staff, s: SanctionRow): void {
  if (actor.role === 'admin') return
  if (s.created_role === 'admin' || s.kind === 'account_ban') {
    throw forbidden('admin_only', 'Only admins can change sanctions given by admins')
  }
}

/** Minuten aus Vorlage bzw. eigener Dauer; `null` = dauerhaft. */
export function minutesOf(preset: DurationPreset, minutes?: number): number | null {
  if (preset === 'permanent') return null
  if (preset === 'custom') {
    if (minutes === undefined) throw badRequest('invalid_duration', 'minutes is required for duration=custom')
    return minutes
  }
  return DURATIONS[preset]
}

// ---------------------------------------------------------------- Verhängen

export interface SanctionInput {
  uuid: string
  kind: SanctionKind
  /** `null` = dauerhaft. */
  minutes: number | null
  reasonCode: ReasonCode
  reason?: string | null
  note?: string | null
  reportId?: string | null
  auto?: 'reports' | 'spam'
}

function publishAdded(ctx: AppContext, s: SanctionRow): void {
  ctx.events.publish(s.uuid, { type: 'sanction_added', sanction: mySanctionView(ctx, s) }, { meOnly: true })
  // Älteres Ereignis für Chat-Clients (§20), unverändert.
  if (s.kind === 'warn') ctx.events.publish(s.uuid, { type: 'moderation', action: 'warn', reason: s.reason, until: null })
  if (s.kind === 'chat_mute') ctx.events.publish(s.uuid, { type: 'moderation', action: 'mute', reason: s.reason, until: isoOrNull(s.expires_at) })
}

/** Wirkung einer neuen Strafe über die Datenbank hinaus (Sitzungen, Präsenz, Räume, Streams). */
function applyEffects(ctx: AppContext, s: SanctionRow): void {
  if (s.kind === 'hosting_ban') endHostingFor(ctx, s.uuid)
  if (s.kind === 'account_ban') {
    if (ctx.presence.delete(s.uuid)) broadcastPresence(ctx, s.uuid)
    endHostingFor(ctx, s.uuid)
    // Beobachter (Mod) vergessen Abzeichen, Umhang und Kosmetik sofort.
    emitCleared(ctx, s.uuid)
    ctx.events.kick(s.uuid)
    ctx.watch.kick(s.uuid)
  }
}

/** Neue Strafe (prüft Rechte, außer bei Automatik). */
export function createSanction(ctx: AppContext, actor: Staff, input: SanctionInput): SanctionRow {
  if (!input.auto) {
    assertCanTarget(ctx, actor, input.uuid)
    assertDurationAllowed(actor, input.kind, input.minutes)
  } else if (staffRole(ctx, input.uuid)) {
    throw conflict('cannot_moderate_admin', 'Team members are not sanctioned automatically')
  }
  if (input.minutes !== null && (!Number.isInteger(input.minutes) || input.minutes < 1 || input.minutes > MAX_CUSTOM_MINUTES)) {
    throw badRequest('invalid_duration', 'Invalid duration')
  }
  const t = ctx.now()
  const expires = input.minutes === null ? null : t + input.minutes * 60_000
  const role: SanctionRow['created_role'] = actor === SYSTEM || actor.uuid === 'system' ? 'system' : actor.role
  const id = tx(ctx.db, () => {
    // Eine Entscheidung des Teams ersetzt eine automatische Stummschaltung.
    if (input.kind === 'chat_mute' && !input.auto) {
      run(
        ctx.db,
        `UPDATE sanctions SET lifted_at = ?, lifted_by = ?, lift_reason = 'replaced', updated_at = ?
         WHERE uuid = ? AND kind = 'chat_mute' AND lifted_at IS NULL AND auto IS NOT NULL`,
        t, actor.uuid, t, input.uuid,
      )
    }
    const row = one<{ id: number }>(
      ctx.db,
      `INSERT INTO sanctions (uuid, kind, reason_code, reason, note, report_id, auto, created_at, created_by, created_role, expires_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id`,
      input.uuid, input.kind, input.reasonCode, input.reason ?? null, input.note ?? null, input.reportId ?? null,
      input.auto ?? null, t, actor.uuid, role, expires, t,
    )!
    if (input.kind === 'account_ban') {
      run(ctx.db, 'DELETE FROM sessions WHERE uuid = ?', input.uuid)
      run(ctx.db, 'DELETE FROM web_sessions WHERE uuid = ?', input.uuid)
    }
    const action = input.auto ? `chat.automute.${input.auto}` : AUDIT_ADD[input.kind]
    const duration = input.minutes === null ? (input.auto === 'reports' ? 'until review' : 'permanent') : `${input.minutes} min`
    audit(ctx, actor.uuid, action, input.uuid, input.kind === 'warn' ? (input.reason ?? input.reasonCode) : `${duration} · ${input.reasonCode}`, input.reportId ?? `s${row.id}`)
    return row.id
  })
  const s = getSanction(ctx, id)!
  publishAdded(ctx, s)
  applyEffects(ctx, s)
  return s
}

// ---------------------------------------------------------------- Aufheben, verkürzen, verlängern

function publishUpdated(ctx: AppContext, s: SanctionRow): void {
  ctx.events.publish(s.uuid, { type: 'sanction_updated', sanction: mySanctionView(ctx, s) }, { meOnly: true })
  if (s.kind !== 'chat_mute') return
  const m = activeSanction(ctx, s.uuid, 'chat_mute')
  if (!m) ctx.events.publish(s.uuid, { type: 'moderation', action: 'unmute', reason: null, until: null })
  else if (m.id === s.id) ctx.events.publish(s.uuid, { type: 'moderation', action: 'mute', reason: m.reason, until: isoOrNull(m.expires_at) })
}

function liftInTx(ctx: AppContext, actor: Staff, s: SanctionRow, reason: string, auditAction?: string): void {
  const t = ctx.now()
  run(ctx.db, 'UPDATE sanctions SET lifted_at = ?, lifted_by = ?, lift_reason = ?, updated_at = ? WHERE id = ?', t, actor.uuid, reason, t, s.id)
  run(
    ctx.db,
    "INSERT INTO sanction_changes (sanction_id, at, actor, action, old_expires_at, new_expires_at, reason) VALUES (?, ?, ?, 'lift', ?, ?, ?)",
    s.id, t, actor.uuid, s.expires_at, t, reason,
  )
  const action = auditAction ?? (s.kind === 'chat_mute' ? 'chat.unmute' : s.kind === 'account_ban' ? 'user.unban' : 'sanction.lift')
  audit(ctx, actor.uuid, action, s.uuid, `${s.kind} #${s.id}: ${reason}`, s.report_id ?? `s${s.id}`)
}

/** Aktive Strafe aufheben (mit Begründung). */
export function liftSanction(ctx: AppContext, actor: Staff, id: number, reason: string): SanctionRow {
  const s = sanctionOr404(ctx, id)
  if (statusOf(s, ctx.now()) !== 'active') throw conflict('sanction_not_active', 'This sanction is no longer active')
  assertCanModify(actor, s)
  tx(ctx.db, () => liftInTx(ctx, actor, s, reason))
  const next = getSanction(ctx, id)!
  publishUpdated(ctx, next)
  return next
}

/** Ende ändern: `endsAt = null` = dauerhaft. Muss in der Zukunft liegen (sonst: aufheben). */
export function changeDuration(ctx: AppContext, actor: Staff, id: number, endsAt: number | null, reason: string, opts: { auditAction?: string } = {}): SanctionRow {
  const s = sanctionOr404(ctx, id)
  const t = ctx.now()
  if (statusOf(s, t) !== 'active') throw conflict('sanction_not_active', 'This sanction is no longer active')
  assertCanModify(actor, s)
  if (endsAt !== null && endsAt <= t) throw badRequest('invalid_duration', 'The new end must be in the future – lift the sanction instead')
  if (endsAt !== null && endsAt > s.created_at + MAX_CUSTOM_MINUTES * 60_000) throw badRequest('invalid_duration', 'Invalid duration')
  assertDurationAllowed(actor, s.kind, endsAt === null ? null : Math.ceil((endsAt - s.created_at) / 60_000))
  if (endsAt === s.expires_at) throw conflict('no_change', 'The sanction already ends then')
  const action: 'shorten' | 'extend' = s.expires_at === null ? 'shorten' : endsAt === null || endsAt > s.expires_at ? 'extend' : 'shorten'
  tx(ctx.db, () => {
    run(ctx.db, 'UPDATE sanctions SET expires_at = ?, updated_at = ? WHERE id = ?', endsAt, t, s.id)
    run(
      ctx.db,
      'INSERT INTO sanction_changes (sanction_id, at, actor, action, old_expires_at, new_expires_at, reason) VALUES (?, ?, ?, ?, ?, ?, ?)',
      s.id, t, actor.uuid, action, s.expires_at, endsAt, reason,
    )
    audit(ctx, actor.uuid, opts.auditAction ?? `sanction.${action}`, s.uuid, `${s.kind} #${s.id} → ${endsAt === null ? 'permanent' : iso(endsAt)}: ${reason}`, s.report_id ?? `s${s.id}`)
  })
  const next = getSanction(ctx, id)!
  publishUpdated(ctx, next)
  return next
}

/**
 * Alle aktiven Strafen einer Art aufheben (alte Routen: Stumm aufheben, Entsperren). `onlyAuto` = nur
 * automatische Stummschaltungen. Rückgabe: Anzahl. Rechte je Strafe wie bei {@link liftSanction}.
 */
export function liftActive(
  ctx: AppContext,
  actor: Staff,
  uuid: string,
  kind: SanctionKind,
  reason: string,
  opts: { onlyAuto?: 'reports', auditAction?: string } = {},
): number {
  const t = ctx.now()
  const rows = all<SanctionRow>(
    ctx.db,
    `SELECT * FROM sanctions WHERE uuid = ? AND kind = ? AND lifted_at IS NULL AND (expires_at IS NULL OR expires_at > ?)
       ${opts.onlyAuto ? 'AND auto = ?' : ''}`,
    ...(opts.onlyAuto ? [uuid, kind, t, opts.onlyAuto] : [uuid, kind, t]),
  )
  if (rows.length === 0) return 0
  for (const s of rows) assertCanModify(actor, s)
  tx(ctx.db, () => {
    for (const s of rows) liftInTx(ctx, actor, s, reason, opts.auditAction)
  })
  for (const s of rows) publishUpdated(ctx, getSanction(ctx, s.id)!)
  return rows.length
}

// ---------------------------------------------------------------- Listen

export interface SanctionListQuery {
  status: SanctionStatus | 'all'
  kind?: SanctionKind
  uuid?: string
  actor?: string
  from?: number
  to?: number
  sort: 'newest' | 'oldest'
  cursor?: string
  limit: number
}

const CURSOR = /^(\d{1,15}):(\d{1,15})$/

export function encodeCursor(a: number, b: number | string): string {
  return Buffer.from(`${a}:${b}`).toString('base64url')
}

export function decodeCursor(cursor: string): [number, number] {
  const m = CURSOR.exec(Buffer.from(cursor, 'base64url').toString('utf8'))
  if (!m) throw badRequest('invalid_cursor', 'Invalid cursor')
  return [Number(m[1]), Number(m[2])]
}

export function listSanctions(ctx: AppContext, q: SanctionListQuery): { sanctions: AdminSanctionView[], nextCursor: string | null } {
  const where: string[] = []
  const params: (string | number)[] = []
  const t = ctx.now()
  if (q.status === 'active') {
    where.push('lifted_at IS NULL AND (expires_at IS NULL OR expires_at > ?)')
    params.push(t)
  } else if (q.status === 'expired') {
    where.push('lifted_at IS NULL AND expires_at IS NOT NULL AND expires_at <= ?')
    params.push(t)
  } else if (q.status === 'lifted') {
    where.push('lifted_at IS NOT NULL')
  }
  if (q.kind) {
    where.push('kind = ?')
    params.push(q.kind)
  }
  if (q.uuid) {
    where.push('uuid = ?')
    params.push(q.uuid)
  }
  if (q.actor) {
    where.push('created_by = ?')
    params.push(q.actor)
  }
  if (q.from !== undefined) {
    where.push('created_at >= ?')
    params.push(q.from)
  }
  if (q.to !== undefined) {
    where.push('created_at < ?')
    params.push(q.to)
  }
  const asc = q.sort === 'oldest'
  if (q.cursor) {
    const [at, id] = decodeCursor(q.cursor)
    where.push(asc ? '(created_at > ? OR (created_at = ? AND id > ?))' : '(created_at < ? OR (created_at = ? AND id < ?))')
    params.push(at, at, id)
  }
  // Bedingungen nur aus festen Bausteinen oben, Werte als Parameter.
  const rows = all<SanctionRow>(
    ctx.db,
    `SELECT * FROM sanctions ${where.length ? `WHERE ${where.join(' AND ')}` : ''}
     ORDER BY created_at ${asc ? 'ASC' : 'DESC'}, id ${asc ? 'ASC' : 'DESC'} LIMIT ?`,
    ...params, q.limit + 1,
  )
  const page = rows.slice(0, q.limit)
  const last = page[page.length - 1]
  return {
    sanctions: adminSanctionViews(ctx, page),
    nextCursor: rows.length > q.limit && last ? encodeCursor(last.created_at, last.id) : null,
  }
}

/** Alle Strafen eines Spielers (neueste zuerst) – für die Akte. */
export function sanctionsOf(ctx: AppContext, uuid: string, limit = 200): SanctionRow[] {
  return all<SanctionRow>(ctx.db, 'SELECT * FROM sanctions WHERE uuid = ? ORDER BY created_at DESC, id DESC LIMIT ?', uuid, limit)
}

/** Aktive Strafen nach Art (Dashboard). */
export function activeCounts(ctx: AppContext): Record<SanctionKind, number> {
  const out = Object.fromEntries(SANCTION_KINDS.map((k) => [k, 0])) as Record<SanctionKind, number>
  for (const r of all<{ kind: SanctionKind, n: number }>(
    ctx.db,
    'SELECT kind, COUNT(DISTINCT uuid) AS n FROM sanctions WHERE lifted_at IS NULL AND (expires_at IS NULL OR expires_at > ?) GROUP BY kind',
    ctx.now(),
  )) out[r.kind] = r.n
  return out
}

// ---------------------------------------------------------------- Spieler: eigene Strafen und Einspruch

/** Eigene Strafen: aktive und vergangene (bis zum Ende der Aufbewahrung), neueste zuerst. */
export function mySanctions(ctx: AppContext, uuid: string): { active: MySanctionView[], past: MySanctionView[] } {
  const views = sanctionsOf(ctx, uuid, 200).map((s) => mySanctionView(ctx, s))
  return { active: views.filter((v) => v.status === 'active'), past: views.filter((v) => v.status !== 'active') }
}

export function createAppeal(ctx: AppContext, uuid: string, sanctionId: number, text: string): MySanctionView {
  const s = getSanction(ctx, sanctionId)
  if (!s || s.uuid !== uuid) throw notFound('sanction_not_found', 'Sanction not found')
  if (statusOf(s, ctx.now()) !== 'active') throw conflict('sanction_not_active', 'You can only appeal active sanctions')
  if (appealOf(ctx, s.id)) throw conflict('appeal_exists', 'You already appealed this sanction')
  const t = ctx.now()
  tx(ctx.db, () => {
    const a = one<{ id: number }>(
      ctx.db,
      "INSERT INTO sanction_appeals (sanction_id, uuid, text, status, created_at) VALUES (?, ?, ?, 'open', ?) RETURNING id",
      s.id, uuid, text, t,
    )!
    audit(ctx, uuid, 'appeal.create', uuid, `${s.kind} #${s.id}`, `a${a.id}`)
  })
  const v = mySanctionView(ctx, getSanction(ctx, s.id)!)
  ctx.events.publish(uuid, { type: 'sanction_updated', sanction: v }, { meOnly: true })
  return v
}

// ---------------------------------------------------------------- Team: Einsprüche

export interface AdminAppealListItem {
  id: number
  status: AppealStatus
  text: string
  createdAt: string
  decidedAt: string | null
  decidedBy: ActorRef | null
  response: string | null
  sanction: AdminSanctionView
}

export function listAppeals(ctx: AppContext, q: { status: 'open' | 'decided' | 'all', cursor?: string, limit: number }): { appeals: AdminAppealListItem[], nextCursor: string | null, open: number } {
  const where: string[] = []
  const params: (string | number)[] = []
  if (q.status === 'open') where.push("a.status = 'open'")
  else if (q.status === 'decided') where.push("a.status <> 'open'")
  const asc = q.status === 'open'
  if (q.cursor) {
    const [at, id] = decodeCursor(q.cursor)
    where.push(asc ? '(a.created_at > ? OR (a.created_at = ? AND a.id > ?))' : '(a.created_at < ? OR (a.created_at = ? AND a.id < ?))')
    params.push(at, at, id)
  }
  const rows = all<AppealRow>(
    ctx.db,
    `SELECT a.* FROM sanction_appeals a ${where.length ? `WHERE ${where.join(' AND ')}` : ''}
     ORDER BY a.created_at ${asc ? 'ASC' : 'DESC'}, a.id ${asc ? 'ASC' : 'DESC'} LIMIT ?`,
    ...params, q.limit + 1,
  )
  const page = rows.slice(0, q.limit)
  const last = page[page.length - 1]
  const sanctions = new Map(adminSanctionViews(ctx, page.map((a) => getSanction(ctx, a.sanction_id)!)).map((s) => [s.id, s]))
  return {
    appeals: page.map((a) => {
      const s = sanctions.get(a.sanction_id)!
      return { id: a.id, status: a.status, text: a.text, createdAt: iso(a.created_at), decidedAt: isoOrNull(a.decided_at), decidedBy: s.appeal?.decidedBy ?? null, response: a.response, sanction: s }
    }),
    nextCursor: rows.length > q.limit && last ? encodeCursor(last.created_at, last.id) : null,
    open: one<{ n: number }>(ctx.db, "SELECT COUNT(*) AS n FROM sanction_appeals WHERE status = 'open'")!.n,
  }
}

export interface AppealDecision {
  decision: 'lift' | 'shorten' | 'uphold'
  /** Antwort an den Spieler (sieht er in `/v1/me/sanctions` und im Ereignis). */
  response: string
  /** Nur `shorten`: neues Ende (muss vor dem bisherigen liegen). */
  endsAt?: number
}

export function decideAppeal(ctx: AppContext, actor: Staff, appealId: number, d: AppealDecision): AdminAppealListItem {
  const a = one<AppealRow>(ctx.db, 'SELECT * FROM sanction_appeals WHERE id = ?', appealId)
  if (!a) throw notFound('appeal_not_found', 'Appeal not found')
  if (a.status !== 'open') throw conflict('appeal_decided', 'This appeal was already decided')
  const s = getSanction(ctx, a.sanction_id)!
  if (actor.role !== 'admin' && s.created_by === actor.uuid) {
    throw forbidden('own_sanction', 'Appeals against your own sanctions are decided by someone else')
  }
  const t = ctx.now()
  const active = statusOf(s, t) === 'active'
  let status: AppealStatus = 'upheld'
  if (d.decision === 'lift') {
    if (active) liftSanction(ctx, actor, s.id, `Appeal: ${d.response}`.slice(0, 500))
    status = 'lifted'
  } else if (d.decision === 'shorten') {
    if (d.endsAt === undefined) throw badRequest('invalid_request', 'endsAt is required for decision=shorten')
    if (!active) throw conflict('sanction_not_active', 'This sanction is no longer active')
    if (s.expires_at !== null && d.endsAt >= s.expires_at) throw badRequest('invalid_duration', 'Shortening must move the end earlier')
    changeDuration(ctx, actor, s.id, d.endsAt, `Appeal: ${d.response}`.slice(0, 500))
    status = 'shortened'
  } else {
    // Bestehen lassen: Rechte wie beim Ändern nicht nötig, aber eigene Strafen oben ausgeschlossen.
  }
  tx(ctx.db, () => {
    run(
      ctx.db,
      'UPDATE sanction_appeals SET status = ?, decided_at = ?, decided_by = ?, response = ? WHERE id = ?',
      status, t, actor.uuid, d.response, a.id,
    )
    audit(ctx, actor.uuid, `appeal.${status}`, s.uuid, `${s.kind} #${s.id}`, `a${a.id}`)
  })
  const next = getSanction(ctx, s.id)!
  const v = mySanctionView(ctx, next)
  ctx.events.publish(s.uuid, { type: 'appeal_decided', sanctionId: s.id, appeal: v.appeal!, sanction: v }, { meOnly: true })
  const view = adminSanctionView(ctx, next)
  const decided = one<AppealRow>(ctx.db, 'SELECT * FROM sanction_appeals WHERE id = ?', a.id)!
  return { id: decided.id, status: decided.status, text: decided.text, createdAt: iso(decided.created_at), decidedAt: isoOrNull(decided.decided_at), decidedBy: view.appeal?.decidedBy ?? null, response: decided.response, sanction: view }
}

// ---------------------------------------------------------------- Aufbewahrung, Kontolöschung

export const SANCTION_RETENTION_MS = 2 * 365 * 86_400_000

/** Strafen (samt Änderungen und Einspruch) 2 Jahre nach ihrem Ende löschen; interne Notizen nach 2 Jahren. */
export function sweepSanctions(ctx: AppContext): void {
  const cut = ctx.now() - SANCTION_RETENTION_MS
  run(ctx.db, 'DELETE FROM sanctions WHERE (lifted_at IS NOT NULL AND lifted_at < ?) OR (lifted_at IS NULL AND expires_at IS NOT NULL AND expires_at < ?)', cut, cut)
  run(ctx.db, 'DELETE FROM player_notes WHERE at < ?', cut)
  run(
    ctx.db,
    'DELETE FROM name_history WHERE last_seen < ? AND name <> (SELECT u.name FROM users u WHERE u.uuid = name_history.uuid)',
    cut,
  )
  run(ctx.db, 'DELETE FROM admin_log WHERE at < ? AND ref IS NULL', cut)
}

/**
 * Kontolöschung: Verwarnungen und beendete Strafen samt Einsprüchen weg; **aktive** Strafen bleiben
 * (sonst ließen sie sich per Löschen umgehen). Notizen bleiben nur, solange eine Strafe aktiv ist.
 */
export function purgeSanctions(ctx: AppContext, uuid: string): void {
  const t = ctx.now()
  run(
    ctx.db,
    "DELETE FROM sanctions WHERE uuid = ? AND (kind = 'warn' OR lifted_at IS NOT NULL OR (expires_at IS NOT NULL AND expires_at <= ?))",
    uuid, t,
  )
  if (!one(ctx.db, 'SELECT 1 AS x FROM sanctions WHERE uuid = ?', uuid)) run(ctx.db, 'DELETE FROM player_notes WHERE uuid = ?', uuid)
}

/** Einfacher Name für Logs/Ansichten. */
export function playerName(ctx: AppContext, uuid: string): string | null {
  return getUser(ctx, uuid)?.name ?? null
}
