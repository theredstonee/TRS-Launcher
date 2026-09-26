import { randomBytes } from 'node:crypto'
import { attachmentsOf, copyToEvidence, getAttachment, removeEvidenceFiles } from './attachments'
import { audit } from './audit'
import {
  access,
  accessMessage,
  decryptForEvidence,
  deleteMessage,
  getMessage,
  groupNameOf,
  memberUuids,
  type ConversationRow,
  type InviteView,
  type MessageRow,
} from './chat'
import type { AppContext } from './context'
import { all, one, placeholders, run, tx } from './db'
import { badRequest, conflict, notFound } from './errors'
import type { PlayerRef } from './events'
import {
  MOD_MAX_WARN_MINUTES,
  SYSTEM,
  activeSanction,
  createSanction,
  liftActive,
  minutesOf,
  purgeSanctions,
  staffOf,
  statusOf,
  sweepSanctions,
  type DurationPreset,
  type ReasonCode,
  type SanctionKind,
  type SanctionRow,
  type Staff,
} from './sanctions'
import { getUser, staffRole } from './users'

/**
 * Moderation für den Chat: Sanktionen (Verwarnung, Stummschaltung), Meldungen mit
 * Beweis-Schnappschuss, Admin-Aktionen mit Audit-Log, Automatik (Spam-Bremse, Auto-Stumm bei
 * vielen Meldern) und Missbrauchsschutz für Meldungen.
 */

export const REPORT_ID = /^r[0-9a-f]{16}$/
export const REPORT_REASONS = ['insult_hate', 'spam', 'inappropriate', 'scam_phishing', 'harassment', 'other'] as const
export type ReportReason = (typeof REPORT_REASONS)[number]
export type ReportKind = 'message' | 'image' | 'player' | 'group'
export type ReportStatus = 'open' | 'in_review' | 'resolved'
export type ReportOutcome = 'actioned' | 'dismissed'

const iso = (t: number) => new Date(t).toISOString()
const isoOrNull = (t: number | null) => (t === null ? null : iso(t))

// ---------------------------------------------------------------- Sanktionen (Chat-Sicht auf §22)

/**
 * Ältere Sicht auf Chat-Strafen (§20.5, `targetModeration`, `/v1/admin/moderation/users/*`): nur Verwarnung
 * und Chat-Stumm, `kind` wie früher `warn`/`mute`. Gespeichert wird alles in `sanctions` (sanctions.ts).
 */
export interface SanctionView {
  id: number
  kind: 'warn' | 'mute'
  reason: string | null
  reportId: string | null
  auto: 'reports' | 'spam' | null
  createdAt: string
  createdBy: string
  /** Ende der Stummschaltung; `null` = bis zur Prüfung bzw. unbefristet. */
  expiresAt: string | null
  liftedAt: string | null
  liftedBy: string | null
  active: boolean
}

export type { SanctionRow }

function sanctionView(ctx: AppContext, s: SanctionRow): SanctionView {
  return {
    id: s.id,
    kind: s.kind === 'warn' ? 'warn' : 'mute',
    reason: s.reason,
    reportId: s.report_id,
    auto: s.auto,
    createdAt: iso(s.created_at),
    createdBy: s.created_by,
    expiresAt: isoOrNull(s.expires_at),
    liftedAt: isoOrNull(s.lifted_at),
    liftedBy: s.lifted_by,
    active: s.kind === 'chat_mute' && statusOf(s, ctx.now()) === 'active',
  }
}

function chatSanctions(ctx: AppContext, uuid: string, limit: number): SanctionView[] {
  return all<SanctionRow>(
    ctx.db,
    "SELECT * FROM sanctions WHERE uuid = ? AND kind IN ('warn', 'chat_mute') ORDER BY created_at DESC, id DESC LIMIT ?",
    uuid, limit,
  ).map((s) => sanctionView(ctx, s))
}

/** Aktive Stummschaltung (die am längsten laufende), sonst `undefined`. */
export function activeMute(ctx: AppContext, uuid: string): SanctionRow | undefined {
  return activeSanction(ctx, uuid, 'chat_mute')
}

const toStaff = (ctx: AppContext, actor: string | Staff): Staff => (typeof actor === 'string' ? staffOf(ctx, actor) : actor)

/** Stummschalten. `minutes = null` = bis zur Prüfung/unbefristet. */
export function muteUser(
  ctx: AppContext,
  actor: string | Staff,
  uuid: string,
  minutes: number | null,
  reason: string | null,
  opts: { reportId?: string, auto?: 'reports' | 'spam', reasonCode?: ReasonCode, note?: string | null } = {},
): SanctionView {
  const s = createSanction(ctx, opts.auto ? SYSTEM : toStaff(ctx, actor), {
    uuid,
    kind: 'chat_mute',
    minutes,
    reasonCode: opts.reasonCode ?? (opts.auto ? `auto_${opts.auto}` as const : 'other'),
    reason,
    note: opts.note ?? null,
    reportId: opts.reportId ?? null,
    auto: opts.auto,
  })
  return sanctionView(ctx, s)
}

/** Hebt Stummschaltungen auf (`onlyAuto` = nur automatische). Rückgabe: Anzahl. */
export function unmuteUser(ctx: AppContext, actor: string | Staff, uuid: string, opts: { onlyAuto?: 'reports', reportId?: string } = {}): number {
  return liftActive(ctx, toStaff(ctx, actor), uuid, 'chat_mute', opts.onlyAuto ? 'Automatic mute ended: reports reviewed' : 'Lifted', {
    onlyAuto: opts.onlyAuto,
    auditAction: opts.onlyAuto ? 'chat.unmute.auto' : 'chat.unmute',
  })
}

/** Verwarnung (gilt 30 Tage, wenn nichts anderes angegeben). */
export function warnUser(
  ctx: AppContext,
  actor: string | Staff,
  uuid: string,
  reason: string | null,
  reportId?: string,
  opts: { minutes?: number | null, reasonCode?: ReasonCode, note?: string | null } = {},
): SanctionView {
  const s = createSanction(ctx, toStaff(ctx, actor), {
    uuid,
    kind: 'warn',
    minutes: opts.minutes === undefined ? MOD_MAX_WARN_MINUTES : opts.minutes,
    reasonCode: opts.reasonCode ?? 'other',
    reason,
    note: opts.note ?? null,
    reportId: reportId ?? null,
  })
  return sanctionView(ctx, s)
}

/** Spam-Bremse: 3 Treffer in 10 Minuten → 10 Minuten automatisch stumm. */
export function applySpamStrike(ctx: AppContext, uuid: string): void {
  if (ctx.spam.strike(uuid) >= 3 && !activeMute(ctx, uuid) && !staffRole(ctx, uuid)) {
    muteUser(ctx, 'system', uuid, 10, 'Automatic: spam', { auto: 'spam' })
  }
}

export interface MyModeration {
  mute: { until: string | null, reason: string | null, auto: 'reports' | 'spam' | null } | null
  /** Verwarnungen der letzten 90 Tage, neueste zuerst. */
  warnings: { reason: string | null, at: string }[]
}

export function myModeration(ctx: AppContext, uuid: string): MyModeration {
  const m = activeMute(ctx, uuid)
  const warnings = all<{ reason: string | null, created_at: number }>(
    ctx.db,
    "SELECT reason, created_at FROM sanctions WHERE uuid = ? AND kind = 'warn' AND lifted_at IS NULL AND created_at > ? ORDER BY created_at DESC LIMIT 20",
    uuid, ctx.now() - 90 * 86_400_000,
  )
  return {
    mute: m ? { until: isoOrNull(m.expires_at), reason: m.reason, auto: m.auto } : null,
    warnings: warnings.map((w) => ({ reason: w.reason, at: iso(w.created_at) })),
  }
}

// ---------------------------------------------------------------- Meldungen

export interface ReportRow {
  id: string
  reporter_uuid: string | null
  target_uuid: string | null
  kind: ReportKind
  conversation_id: string | null
  message_id: string | null
  attachment_id: string | null
  reason: ReportReason
  note: Uint8Array | null
  evidence: Uint8Array | null
  status: ReportStatus
  outcome: ReportOutcome | null
  low_trust: number
  assigned_to: string | null
  created_at: number
  updated_at: number
  resolved_at: number | null
  resolved_by: string | null
  evidence_purged_at: number | null
}

export interface EvidenceMessage {
  id: string
  seq: number
  kind: 'text' | 'system'
  sender: PlayerRef | null
  text: string | null
  invite: InviteView | null
  /** Weltkarte (§21): Raum-Id und Weltname zur Meldezeit. Fehlt in älteren Beweisen. */
  world?: { roomId: string, name: string } | null
  system: { event: string, target: string | null, name: string | null } | null
  attachments: { id: string, width: number, height: number, mime: string }[]
  replyTo: string | null
  createdAt: string
  editedAt: string | null
  deleted: boolean
}

export interface Evidence {
  v: 1
  capturedAt: string
  reporter: PlayerRef | null
  target: PlayerRef | null
  conversation: { id: string, kind: 'dm' | 'group', name: string | null, owner: string | null, members: PlayerRef[] } | null
  /** Die gemeldete Nachricht (bei Nachricht/Bild), sonst `null`. */
  focus: string | null
  /** Bis zu 10 Nachrichten davor + die gemeldete + bis zu 10 danach (bzw. die letzten 20). */
  messages: EvidenceMessage[]
  /** Bilder, von denen eine Kopie aufbewahrt wird (Admin: `GET /v1/admin/reports/{id}/images/{attachmentId}`). */
  images: string[]
}

/** Was der Melder über seine Meldung sieht. */
export interface MyReportView {
  id: string
  kind: ReportKind
  reason: ReportReason
  status: ReportStatus
  outcome: ReportOutcome | null
  createdAt: string
  updatedAt: string
}

function myReportView(r: ReportRow): MyReportView {
  return { id: r.id, kind: r.kind, reason: r.reason, status: r.status, outcome: r.outcome, createdAt: iso(r.created_at), updatedAt: iso(r.updated_at) }
}

export interface ReportInput {
  kind: ReportKind
  reason: ReportReason
  note?: string
  messageId?: string
  attachmentId?: string
  uuid?: string
  conversationId?: string
}

const ref = (ctx: AppContext, uuid: string | null): PlayerRef | null => (uuid ? { uuid, name: getUser(ctx, uuid)?.name ?? '' } : null)

function snapshotMessages(ctx: AppContext, rows: MessageRow[]): EvidenceMessage[] {
  const atts = attachmentsOf(ctx, rows.map((r) => r.id))
  const nm = new Map<string, string>()
  const name = (u: string) => {
    if (!nm.has(u)) nm.set(u, getUser(ctx, u)?.name ?? '')
    return nm.get(u)!
  }
  return rows.map((r) => {
    const c = decryptForEvidence(ctx, r)
    return {
      id: r.id,
      seq: r.seq,
      kind: r.kind,
      sender: r.sender_uuid ? { uuid: r.sender_uuid, name: name(r.sender_uuid) } : null,
      text: c.text,
      invite: c.invite,
      world: c.world,
      system: c.system ? { event: c.system.e, target: c.system.tg ?? null, name: c.system.n ?? null } : null,
      attachments: (atts.get(r.id) ?? []).map((a) => ({ id: a.id, width: a.width, height: a.height, mime: a.mime })),
      replyTo: r.reply_to,
      createdAt: iso(r.created_at),
      editedAt: isoOrNull(r.edited_at),
      deleted: r.deleted_at !== null,
    }
  })
}

function around(ctx: AppContext, conversationId: string, from: number, focusSeq: number): MessageRow[] {
  const before = all<MessageRow>(
    ctx.db, 'SELECT * FROM chat_messages WHERE conversation_id = ? AND seq >= ? AND seq < ? ORDER BY seq DESC LIMIT 10',
    conversationId, from, focusSeq,
  ).reverse()
  const rest = all<MessageRow>(
    ctx.db, 'SELECT * FROM chat_messages WHERE conversation_id = ? AND seq >= ? ORDER BY seq ASC LIMIT 11',
    conversationId, focusSeq,
  )
  return [...before, ...rest]
}

function latest(ctx: AppContext, conversationId: string, from: number): MessageRow[] {
  return all<MessageRow>(
    ctx.db, 'SELECT * FROM chat_messages WHERE conversation_id = ? AND seq >= ? ORDER BY seq DESC LIMIT 20', conversationId, from,
  ).reverse()
}

function convSnapshot(ctx: AppContext, conv: ConversationRow): Evidence['conversation'] {
  return {
    id: conv.id,
    kind: conv.kind,
    name: conv.kind === 'group' ? groupNameOf(ctx, conv) : null,
    owner: conv.owner_uuid,
    members: memberUuids(ctx, conv.id).map((u) => ref(ctx, u)!),
  }
}

/** Melder mit vielen abgewiesenen Meldungen zählen nicht für die Auto-Stummschaltung. */
export function reporterTrust(ctx: AppContext, uuid: string): { actioned: number, dismissed: number, low: boolean } {
  const s = one<{ actioned: number, dismissed: number }>(ctx.db, 'SELECT actioned, dismissed FROM chat_reporter_stats WHERE uuid = ?', uuid)
  const actioned = s?.actioned ?? 0
  const dismissed = s?.dismissed ?? 0
  return { actioned, dismissed, low: dismissed >= 3 && dismissed > 2 * actioned }
}

/**
 * Meldung anlegen: prüft den Zugriff (man kann nur melden, was man sieht), macht den
 * Beweis-Schnappschuss (verschlüsselt) und kopiert gemeldete Bilder. Danach ggf. Auto-Stumm.
 */
export function createReport(ctx: AppContext, reporter: string, input: ReportInput): MyReportView {
  const lim = ctx.config.limits
  const open = one<{ n: number }>(
    ctx.db, "SELECT COUNT(*) AS n FROM chat_reports WHERE reporter_uuid = ? AND status <> 'resolved'", reporter,
  )!.n
  if (open >= lim.maxOpenReportsPerUser) throw conflict('too_many_open_reports', 'You have too many open reports – wait until they are reviewed')

  let target: string | null = null
  let conv: ConversationRow | null = null
  let focus: MessageRow | null = null
  let messages: MessageRow[] = []
  const copy: string[] = []
  let messageId: string | null = null
  let attachmentId: string | null = null

  if (input.kind === 'message' || input.kind === 'image') {
    let msgId = input.messageId
    if (input.kind === 'image') {
      if (!input.attachmentId) throw badRequest('invalid_request', 'attachmentId is required')
      const a = getAttachment(ctx, input.attachmentId)
      if (!a?.message_id) throw notFound('attachment_not_found', 'Image not found')
      msgId = a.message_id
      attachmentId = a.id
    }
    if (!msgId) throw badRequest('invalid_request', 'messageId is required')
    let a: ReturnType<typeof accessMessage>
    try {
      a = accessMessage(ctx, reporter, msgId)
    } catch {
      throw notFound(input.kind === 'image' ? 'attachment_not_found' : 'message_not_found', input.kind === 'image' ? 'Image not found' : 'Message not found')
    }
    if (a.msg.kind !== 'text') throw badRequest('not_reportable', 'System messages cannot be reported')
    if (a.msg.deleted_at !== null) throw conflict('message_deleted', 'This message was already deleted')
    if (a.msg.sender_uuid === reporter) throw badRequest('cannot_target_self', 'You cannot report yourself')
    target = a.msg.sender_uuid
    conv = a.conv
    focus = a.msg
    messageId = a.msg.id
    messages = around(ctx, conv.id, a.member.visible_from_seq, a.msg.seq)
    if (input.kind === 'image') copy.push(attachmentId!)
    else copy.push(...(attachmentsOf(ctx, [a.msg.id]).get(a.msg.id) ?? []).map((x) => x.id))
  } else if (input.kind === 'player') {
    if (!input.uuid) throw badRequest('invalid_request', 'uuid is required')
    if (input.uuid === reporter) throw badRequest('cannot_target_self', 'You cannot report yourself')
    if (!getUser(ctx, input.uuid)) throw notFound('player_not_found', 'No TRS user with this UUID')
    target = input.uuid
    if (input.conversationId) {
      const a = access(ctx, reporter, input.conversationId)
      if (!memberUuids(ctx, a.conv.id).includes(target)) throw notFound('conversation_not_found', 'Conversation not found')
      conv = a.conv
      messages = latest(ctx, conv.id, a.member.visible_from_seq)
    }
  } else {
    if (!input.conversationId) throw badRequest('invalid_request', 'conversationId is required')
    const a = access(ctx, reporter, input.conversationId)
    if (a.conv.kind !== 'group') throw notFound('conversation_not_found', 'Conversation not found')
    if (a.conv.owner_uuid === reporter) throw badRequest('cannot_target_self', 'You cannot report your own group')
    conv = a.conv
    target = a.conv.owner_uuid
    messages = latest(ctx, conv.id, a.member.visible_from_seq)
  }

  const dup = one(
    ctx.db,
    `SELECT 1 AS x FROM chat_reports WHERE reporter_uuid = ? AND kind = ? AND status <> 'resolved'
       AND COALESCE(message_id, '') = ? AND COALESCE(attachment_id, '') = ? AND COALESCE(target_uuid, '') = ? AND COALESCE(conversation_id, '') = ?`,
    reporter, input.kind, messageId ?? '', attachmentId ?? '', target ?? '', conv?.id ?? '',
  )
  if (dup) throw conflict('already_reported', 'You already reported this')

  const id = `r${randomBytes(8).toString('hex')}`
  const t = ctx.now()
  const evidence: Evidence = {
    v: 1,
    capturedAt: iso(t),
    reporter: ref(ctx, reporter),
    target: ref(ctx, target),
    conversation: conv ? convSnapshot(ctx, conv) : null,
    focus: focus?.id ?? null,
    messages: snapshotMessages(ctx, messages),
    images: copy,
  }
  const lowTrust = reporterTrust(ctx, reporter).low
  tx(ctx.db, () => {
    run(
      ctx.db,
      `INSERT INTO chat_reports (id, reporter_uuid, target_uuid, kind, conversation_id, message_id, attachment_id, reason, note, evidence,
         status, outcome, low_trust, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', NULL, ?, ?, ?)`,
      id, reporter, target, input.kind, conv?.id ?? null, messageId, attachmentId, input.reason,
      input.note ? ctx.cipher.encrypt(input.note, `rnote:${id}`) : null,
      ctx.cipher.encrypt(JSON.stringify(evidence), `rep:${id}`),
      lowTrust ? 1 : 0, t, t,
    )
  })
  for (const attId of copy) {
    const a = getAttachment(ctx, attId)
    if (a) {
      try {
        copyToEvidence(ctx, id, a)
      } catch (err) {
        console.error(`[trs-api] could not copy evidence image ${attId}`, (err as Error).message)
      }
    }
  }
  if (target && !lowTrust) maybeAutoMute(ctx, target, id)
  return myReportView(one<ReportRow>(ctx.db, 'SELECT * FROM chat_reports WHERE id = ?', id)!)
}

/** Auto-Stumm (bis zur Prüfung), wenn genug verschiedene vertrauenswürdige Melder im Fenster melden. */
function maybeAutoMute(ctx: AppContext, target: string, reportId: string): void {
  const lim = ctx.config.limits
  if (staffRole(ctx, target) || activeMute(ctx, target)) return
  const n = one<{ n: number }>(
    ctx.db,
    `SELECT COUNT(DISTINCT reporter_uuid) AS n FROM chat_reports
     WHERE target_uuid = ? AND created_at > ? AND low_trust = 0 AND reporter_uuid IS NOT NULL
       AND (status <> 'resolved' OR outcome = 'actioned')`,
    target, ctx.now() - lim.autoMuteWindowMs,
  )!.n
  if (n >= lim.autoMuteReporters) {
    muteUser(ctx, 'system', target, null, 'Automatic: several reports, pending review', { auto: 'reports', reportId })
  }
}

export function listMyReports(ctx: AppContext, uuid: string): MyReportView[] {
  return all<ReportRow>(ctx.db, 'SELECT * FROM chat_reports WHERE reporter_uuid = ? ORDER BY created_at DESC LIMIT 100', uuid).map(myReportView)
}

// ---------------------------------------------------------------- Admin

export interface AdminReportSummary {
  id: string
  kind: ReportKind
  reason: ReportReason
  status: ReportStatus
  outcome: ReportOutcome | null
  reporter: PlayerRef | null
  target: PlayerRef | null
  conversationId: string | null
  messageId: string | null
  attachmentId: string | null
  /** Anfang des gemeldeten Texts (≤ 140 Zeichen) oder `null`. */
  preview: string | null
  images: number
  lowTrust: boolean
  assignedTo: PlayerRef | null
  /** Offene Meldungen gegen dasselbe Ziel (inkl. dieser). */
  targetOpenReports: number
  /** `high`: schwerer Grund (Hass, Belästigung, Betrug) von vertrauenswürdigem Melder oder ≥ 3 offene gegen das Ziel. */
  priority: 'high' | 'normal'
  createdAt: string
  updatedAt: string
  resolvedAt: string | null
  resolvedBy: string | null
  evidencePurged: boolean
}

export interface AdminReportDetail extends AdminReportSummary {
  note: string | null
  evidence: (Omit<Evidence, 'images'> & { images: { id: string, width: number, height: number, mime: string, path: string }[] }) | null
  notes: { id: number, at: string, actor: string, actorName: string | null, text: string }[]
  audit: { at: string, actor: string, actorName: string | null, action: string, detail: string | null }[]
  targetModeration: {
    mute: SanctionView | null
    sanctions: SanctionView[]
    reports: { total: number, open: number, actioned: number, dismissed: number }
  } | null
  reporterStats: { actioned: number, dismissed: number, low: boolean, open: number } | null
  /** Weitere Meldungen gegen dasselbe Ziel (neueste 20). */
  related: AdminReportSummary[]
}

function readEvidence(ctx: AppContext, r: ReportRow): Evidence | null {
  if (!r.evidence) return null
  try {
    return JSON.parse(ctx.cipher.decryptText(r.evidence, `rep:${r.id}`)) as Evidence
  } catch (err) {
    console.error(`[trs-api] could not decrypt report ${r.id}`, (err as Error).message)
    return null
  }
}

export function summaries(ctx: AppContext, rows: ReportRow[]): AdminReportSummary[] {
  const targets = [...new Set(rows.map((r) => r.target_uuid).filter((u): u is string => !!u))]
  const openCounts = new Map<string, number>()
  if (targets.length) {
    for (const c of all<{ target_uuid: string, n: number }>(
      ctx.db,
      `SELECT target_uuid, COUNT(*) AS n FROM chat_reports WHERE status <> 'resolved' AND target_uuid IN (${placeholders(targets.length)}) GROUP BY target_uuid`,
      ...targets,
    )) openCounts.set(c.target_uuid, c.n)
  }
  return rows.map((r) => {
    const ev = readEvidence(ctx, r)
    const focus = ev?.focus ? ev.messages.find((m) => m.id === ev.focus) : undefined
    return {
      id: r.id,
      kind: r.kind,
      reason: r.reason,
      status: r.status,
      outcome: r.outcome,
      reporter: ref(ctx, r.reporter_uuid),
      target: ref(ctx, r.target_uuid) ?? (ev?.target ?? null),
      conversationId: r.conversation_id,
      messageId: r.message_id,
      attachmentId: r.attachment_id,
      preview: focus?.text ? [...focus.text].slice(0, 140).join('') : null,
      images: ev?.images.length ?? 0,
      lowTrust: r.low_trust === 1,
      assignedTo: ref(ctx, r.assigned_to),
      targetOpenReports: r.target_uuid ? (openCounts.get(r.target_uuid) ?? 0) : 0,
      priority: isHighPriority(r, r.target_uuid ? (openCounts.get(r.target_uuid) ?? 0) : 0) ? 'high' : 'normal',
      createdAt: iso(r.created_at),
      updatedAt: iso(r.updated_at),
      resolvedAt: isoOrNull(r.resolved_at),
      resolvedBy: r.resolved_by,
      evidencePurged: r.evidence_purged_at !== null,
    }
  })
}

/** Gründe, die eine Meldung dringlich machen (§22.5). */
export const HIGH_PRIORITY_REASONS: readonly ReportReason[] = ['insult_hate', 'harassment', 'scam_phishing']
/** So viele offene Meldungen gegen dasselbe Ziel machen jede davon dringlich. */
export const HIGH_PRIORITY_TARGET_OPEN = 3

function isHighPriority(r: Pick<ReportRow, 'reason' | 'low_trust'>, targetOpen: number): boolean {
  return (HIGH_PRIORITY_REASONS.includes(r.reason) && r.low_trust === 0) || targetOpen >= HIGH_PRIORITY_TARGET_OPEN
}

/** SQL-Bedingung für „dringlich“ (gleiche Regel wie {@link isHighPriority}). */
const HIGH_PRIORITY_SQL = `((chat_reports.reason IN ('insult_hate', 'harassment', 'scam_phishing') AND chat_reports.low_trust = 0)
  OR (chat_reports.target_uuid IS NOT NULL AND (SELECT COUNT(*) FROM chat_reports r2
      WHERE r2.target_uuid = chat_reports.target_uuid AND r2.status <> 'resolved') >= ${HIGH_PRIORITY_TARGET_OPEN}))`

export interface ReportListQuery {
  status: ReportStatus | 'active' | 'all'
  kind?: ReportKind
  target?: string
  reason?: ReportReason
  /** Bearbeiter (UUID) oder `none` = niemandem zugewiesen. */
  assigned?: string
  priority?: 'high'
  from?: number
  to?: number
  /** Standard: offene Listen älteste zuerst, sonst neueste zuerst. */
  sort?: 'oldest' | 'newest'
  cursor?: string
  limit: number
}

/** Liste für die Moderation. `active` = offen + in Prüfung. Offene: älteste zuerst, sonst neueste zuerst. */
export function adminListReports(ctx: AppContext, q: ReportListQuery): { reports: AdminReportSummary[], nextCursor: string | null, counts: Record<ReportStatus, number> & { highPriority: number } } {
  const where: string[] = []
  const params: (string | number)[] = []
  if (q.status === 'active') where.push("status <> 'resolved'")
  else if (q.status !== 'all') {
    where.push('status = ?')
    params.push(q.status)
  }
  if (q.kind) {
    where.push('kind = ?')
    params.push(q.kind)
  }
  if (q.target) {
    where.push('target_uuid = ?')
    params.push(q.target)
  }
  if (q.reason) {
    where.push('reason = ?')
    params.push(q.reason)
  }
  if (q.assigned === 'none') where.push('assigned_to IS NULL')
  else if (q.assigned) {
    where.push('assigned_to = ?')
    params.push(q.assigned)
  }
  if (q.priority === 'high') where.push(HIGH_PRIORITY_SQL)
  if (q.from !== undefined) {
    where.push('created_at >= ?')
    params.push(q.from)
  }
  if (q.to !== undefined) {
    where.push('created_at < ?')
    params.push(q.to)
  }
  const asc = q.sort ? q.sort === 'oldest' : q.status === 'open' || q.status === 'active' || q.status === 'in_review'
  if (q.cursor) {
    const m = /^(\d{1,15}):(r[0-9a-f]{16})$/.exec(Buffer.from(q.cursor, 'base64url').toString('utf8'))
    if (!m) throw badRequest('invalid_cursor', 'Invalid cursor')
    where.push(asc ? '(created_at > ? OR (created_at = ? AND id > ?))' : '(created_at < ? OR (created_at = ? AND id < ?))')
    params.push(Number(m[1]), Number(m[1]), m[2]!)
  }
  // Bedingungen nur aus festen Bausteinen oben, Werte als Parameter.
  const rows = all<ReportRow>(
    ctx.db,
    `SELECT * FROM chat_reports ${where.length ? `WHERE ${where.join(' AND ')}` : ''}
     ORDER BY created_at ${asc ? 'ASC' : 'DESC'}, id ${asc ? 'ASC' : 'DESC'} LIMIT ?`,
    ...params, q.limit + 1,
  )
  const page = rows.slice(0, q.limit)
  const last = page[page.length - 1]
  const counts = { open: 0, in_review: 0, resolved: 0, highPriority: 0 }
  for (const c of all<{ status: ReportStatus, n: number }>(ctx.db, 'SELECT status, COUNT(*) AS n FROM chat_reports GROUP BY status')) counts[c.status] = c.n
  counts.highPriority = one<{ n: number }>(ctx.db, `SELECT COUNT(*) AS n FROM chat_reports WHERE status <> 'resolved' AND ${HIGH_PRIORITY_SQL}`)!.n
  return {
    reports: summaries(ctx, page),
    nextCursor: rows.length > q.limit && last ? Buffer.from(`${last.created_at}:${last.id}`).toString('base64url') : null,
    counts,
  }
}

function reportOr404(ctx: AppContext, id: string): ReportRow {
  const r = one<ReportRow>(ctx.db, 'SELECT * FROM chat_reports WHERE id = ?', id)
  if (!r) throw notFound('report_not_found', 'Report not found')
  return r
}

export function adminReportDetail(ctx: AppContext, id: string): AdminReportDetail {
  const r = reportOr404(ctx, id)
  const [summary] = summaries(ctx, [r])
  const ev = readEvidence(ctx, r)
  let note: string | null = null
  if (r.note) {
    try {
      note = ctx.cipher.decryptText(r.note, `rnote:${r.id}`)
    } catch {
      note = null
    }
  }
  const files = new Map(all<{ attachment_id: string, width: number, height: number, mime: string }>(
    ctx.db, 'SELECT attachment_id, width, height, mime FROM chat_evidence_files WHERE report_id = ?', r.id,
  ).map((f) => [f.attachment_id, f]))
  const notes = all<{ id: number, at: number, actor: string, text: Uint8Array, name: string | null }>(
    ctx.db,
    'SELECT n.*, u.name AS name FROM chat_report_notes n LEFT JOIN users u ON u.uuid = n.actor WHERE n.report_id = ? ORDER BY n.at',
    r.id,
  )
  const auditRows = all<{ at: number, actor: string, action: string, detail: string | null, name: string | null }>(
    ctx.db,
    'SELECT l.at, l.actor, l.action, l.detail, u.name AS name FROM admin_log l LEFT JOIN users u ON u.uuid = l.actor WHERE l.ref = ? ORDER BY l.id',
    r.id,
  )
  let targetModeration: AdminReportDetail['targetModeration'] = null
  if (r.target_uuid) {
    const m = activeMute(ctx, r.target_uuid)
    const c = one<{ total: number, open: number, actioned: number, dismissed: number }>(
      ctx.db,
      `SELECT COUNT(*) AS total, COALESCE(SUM(status <> 'resolved'), 0) AS open,
         COALESCE(SUM(outcome = 'actioned'), 0) AS actioned, COALESCE(SUM(outcome = 'dismissed'), 0) AS dismissed
       FROM chat_reports WHERE target_uuid = ?`,
      r.target_uuid,
    )!
    targetModeration = {
      mute: m ? sanctionView(ctx, m) : null,
      sanctions: chatSanctions(ctx, r.target_uuid, 20),
      reports: c,
    }
  }
  let reporterStats: AdminReportDetail['reporterStats'] = null
  if (r.reporter_uuid) {
    const trust = reporterTrust(ctx, r.reporter_uuid)
    reporterStats = {
      ...trust,
      open: one<{ n: number }>(ctx.db, "SELECT COUNT(*) AS n FROM chat_reports WHERE reporter_uuid = ? AND status <> 'resolved'", r.reporter_uuid)!.n,
    }
  }
  const related = r.target_uuid
    ? summaries(ctx, all<ReportRow>(ctx.db, 'SELECT * FROM chat_reports WHERE target_uuid = ? AND id <> ? ORDER BY created_at DESC LIMIT 20', r.target_uuid, r.id))
    : []
  return {
    ...summary!,
    note,
    evidence: ev
      ? {
        ...ev,
        images: ev.images.filter((i) => files.has(i)).map((i) => {
          const f = files.get(i)!
          return { id: i, width: f.width, height: f.height, mime: f.mime, path: `/v1/admin/reports/${r.id}/images/${i}` }
        }),
      }
      : null,
    notes: notes.map((n) => {
      let text: string
      try {
        text = ctx.cipher.decryptText(n.text, `rnotes:${r.id}:${n.id}`)
      } catch {
        text = ''
      }
      return { id: n.id, at: iso(n.at), actor: n.actor, actorName: n.name, text }
    }),
    audit: auditRows.map((a) => ({ at: iso(a.at), actor: a.actor, actorName: a.name, action: a.action, detail: a.detail })),
    targetModeration,
    reporterStats,
    related,
  }
}

function notifyReporter(ctx: AppContext, r: ReportRow): void {
  if (!r.reporter_uuid) return
  ctx.events.publish(r.reporter_uuid, {
    type: 'report_update',
    report: { id: r.id, kind: r.kind, status: r.status, outcome: r.outcome, updatedAt: iso(r.updated_at) },
  })
}

/** Status setzen (offen ↔ in Prüfung). Erledigen geht nur über eine Aktion. */
export function adminSetStatus(ctx: AppContext, actor: string, id: string, status: 'open' | 'in_review'): AdminReportDetail {
  const r = reportOr404(ctx, id)
  if (r.status === 'resolved') throw conflict('report_resolved', 'This report is already resolved')
  if (r.status !== status) {
    run(ctx.db, 'UPDATE chat_reports SET status = ?, assigned_to = ?, updated_at = ? WHERE id = ?', status, status === 'in_review' ? actor : null, ctx.now(), id)
    audit(ctx, actor, `report.${status}`, r.target_uuid, undefined, id)
    notifyReporter(ctx, reportOr404(ctx, id))
  }
  return adminReportDetail(ctx, id)
}

export function adminAddNote(ctx: AppContext, actor: string, id: string, text: string): AdminReportDetail {
  reportOr404(ctx, id)
  tx(ctx.db, () => {
    const noteId = one<{ id: number }>(
      ctx.db, 'INSERT INTO chat_report_notes (report_id, at, actor, text) VALUES (?, ?, ?, ?) RETURNING id', id, ctx.now(), actor, Buffer.alloc(0),
    )!.id
    run(ctx.db, 'UPDATE chat_report_notes SET text = ? WHERE id = ?', ctx.cipher.encrypt(text, `rnotes:${id}:${noteId}`), noteId)
    run(ctx.db, 'UPDATE chat_reports SET updated_at = ? WHERE id = ?', ctx.now(), id)
    audit(ctx, actor, 'report.note', null, undefined, id)
  })
  return adminReportDetail(ctx, id)
}

export type ReportAction = 'delete_message' | 'warn' | 'mute' | 'ban' | 'sanction' | 'dismiss' | 'resolve'

export interface ReportActionInput {
  action: ReportAction
  reason?: string
  /** Nur `mute`: Dauer in Minuten; fehlt = bis zur Aufhebung. `sanction` mit `duration: custom`: eigene Dauer. */
  minutes?: number
  /** Nur `sanction` (§22): Art, Dauer-Vorlage, Grund-Vorlage, interne Notiz. */
  kind?: SanctionKind
  duration?: DurationPreset
  reasonCode?: ReasonCode
  note?: string
  /** Meldung offen lassen (für mehrere Aktionen nacheinander); `dismiss`/`resolve` schließen immer. */
  keepOpen?: boolean
  /** Weitere offene Meldungen zum selben Inhalt (Nachricht/Bild, sonst Ziel+Art) gleich mit erledigen. */
  includeRelated?: boolean
}

/** Meldungs-Grund → Grund-Vorlage der Strafe. */
const REASON_OF_REPORT: Record<ReportReason, ReasonCode> = {
  insult_hate: 'insult_hate',
  spam: 'spam',
  inappropriate: 'inappropriate_content',
  scam_phishing: 'scam_phishing',
  harassment: 'harassment',
  other: 'other',
}

/** Erledigt Meldungen OHNE eigene Transaktion (Aufrufer klammert). Liefert die tatsächlich erledigten. */
function resolveRowsInTx(ctx: AppContext, actor: string, rows: ReportRow[], outcome: ReportOutcome): ReportRow[] {
  const t = ctx.now()
  const done: ReportRow[] = []
  for (const r of rows) {
    if (r.status === 'resolved') continue
    run(
      ctx.db,
      "UPDATE chat_reports SET status = 'resolved', outcome = ?, resolved_at = ?, resolved_by = ?, updated_at = ? WHERE id = ? AND status <> 'resolved'",
      outcome, t, actor, t, r.id,
    )
    if (r.reporter_uuid) {
      run(
        ctx.db,
        `INSERT INTO chat_reporter_stats (uuid, actioned, dismissed) VALUES (?, ?, ?)
         ON CONFLICT(uuid) DO UPDATE SET actioned = actioned + excluded.actioned, dismissed = dismissed + excluded.dismissed`,
        r.reporter_uuid, outcome === 'actioned' ? 1 : 0, outcome === 'dismissed' ? 1 : 0,
      )
    }
    audit(ctx, actor, `report.${outcome}`, r.target_uuid, undefined, r.id)
    done.push(r)
  }
  return done
}

function resolveReports(ctx: AppContext, actor: string, rows: ReportRow[], outcome: ReportOutcome): void {
  for (const r of rows) {
    const done = tx(ctx.db, () => resolveRowsInTx(ctx, actor, [r], outcome))
    for (const d of done) notifyReporter(ctx, reportOr404(ctx, d.id))
  }
}

/** Automatische Stummschaltung „bis zur Prüfung“ endet, wenn gegen das Ziel nichts mehr offen ist. */
function endAutoMuteIfClear(ctx: AppContext, actor: Staff, target: string, reportId: string): void {
  const stillOpen = one<{ n: number }>(ctx.db, "SELECT COUNT(*) AS n FROM chat_reports WHERE target_uuid = ? AND status <> 'resolved'", target)!.n
  if (stillOpen === 0) unmuteUser(ctx, actor, target, { onlyAuto: 'reports', reportId })
}

export function adminReportAction(ctx: AppContext, actorIn: string | Staff, id: string, input: ReportActionInput): AdminReportDetail {
  const actor = toStaff(ctx, actorIn)
  const r = reportOr404(ctx, id)
  const reason = input.reason ?? null
  const reasonCode = input.reasonCode ?? REASON_OF_REPORT[r.reason]
  const note = input.note ?? null
  const needsTarget = input.action === 'warn' || input.action === 'mute' || input.action === 'ban' || input.action === 'sanction'
  if (needsTarget && !r.target_uuid) throw conflict('no_target', 'This report has no player to act on')
  switch (input.action) {
    case 'delete_message': {
      if (!r.message_id) throw conflict('no_message', 'This report is not about a message')
      const m = getMessage(ctx, r.message_id)
      if (m && m.deleted_at === null) deleteMessage(ctx, null, m.id, 'admin')
      audit(ctx, actor.uuid, 'chat.message.delete', r.target_uuid, r.message_id, id)
      break
    }
    case 'warn':
      warnUser(ctx, actor, r.target_uuid!, reason, id, { reasonCode, note })
      break
    case 'mute':
      muteUser(ctx, actor, r.target_uuid!, input.minutes ?? null, reason, { reportId: id, reasonCode, note })
      break
    case 'ban':
      createSanction(ctx, actor, { uuid: r.target_uuid!, kind: 'account_ban', minutes: null, reasonCode, reason, note, reportId: id })
      break
    case 'sanction':
      if (!input.kind || !input.duration) throw badRequest('invalid_request', 'kind and duration are required for action=sanction')
      createSanction(ctx, actor, {
        uuid: r.target_uuid!,
        kind: input.kind,
        minutes: minutesOf(input.duration, input.minutes),
        reasonCode,
        reason,
        note,
        reportId: id,
      })
      break
    case 'dismiss':
    case 'resolve':
      break
  }
  const close = input.action === 'dismiss' || input.action === 'resolve' || !input.keepOpen
  if (close) {
    const rows = [r]
    if (input.includeRelated) {
      const more = r.message_id
        ? all<ReportRow>(ctx.db, "SELECT * FROM chat_reports WHERE message_id = ? AND id <> ? AND status <> 'resolved'", r.message_id, r.id)
        : r.target_uuid
          ? all<ReportRow>(ctx.db, "SELECT * FROM chat_reports WHERE target_uuid = ? AND kind = ? AND id <> ? AND status <> 'resolved'", r.target_uuid, r.kind, r.id)
          : []
      rows.push(...more)
    }
    resolveReports(ctx, actor.uuid, rows, input.action === 'dismiss' ? 'dismissed' : 'actioned')
    // Geprüft: automatische Stummschaltung „bis zur Prüfung“ endet, wenn nichts mehr offen ist
    // (außer das Team hat selbst stummgeschaltet oder gesperrt).
    const restricting = input.action === 'mute' || input.action === 'ban' || (input.action === 'sanction' && (input.kind === 'chat_mute' || input.kind === 'account_ban'))
    if (r.target_uuid && !restricting) endAutoMuteIfClear(ctx, actor, r.target_uuid, id)
  } else if (r.status === 'open') {
    run(ctx.db, "UPDATE chat_reports SET status = 'in_review', assigned_to = ?, updated_at = ? WHERE id = ?", actor.uuid, ctx.now(), id)
    notifyReporter(ctx, reportOr404(ctx, id))
  } else {
    run(ctx.db, 'UPDATE chat_reports SET updated_at = ? WHERE id = ?', ctx.now(), id)
  }
  return adminReportDetail(ctx, id)
}

/** Höchstzahl je Sammelaktion (§22.7). */
export const BULK_MAX = 50

/**
 * Sammelaktion: mehrere Meldungen in EINER Transaktion abweisen bzw. erledigen. Bereits erledigte oder
 * unbekannte IDs werden übersprungen (`skipped`). Danach Rückmeldung an die Melder.
 */
export function adminBulkReports(ctx: AppContext, actorIn: string | Staff, ids: string[], outcome: ReportOutcome): { updated: string[], skipped: string[] } {
  const actor = toStaff(ctx, actorIn)
  const unique = [...new Set(ids)]
  if (unique.length === 0 || unique.length > BULK_MAX) throw badRequest('bulk_too_large', `Between 1 and ${BULK_MAX} items per request`)
  const rows = all<ReportRow>(ctx.db, `SELECT * FROM chat_reports WHERE id IN (${placeholders(unique.length)})`, ...unique)
  const done = tx(ctx.db, () => resolveRowsInTx(ctx, actor.uuid, rows, outcome))
  const doneIds = new Set(done.map((r) => r.id))
  for (const r of done) notifyReporter(ctx, reportOr404(ctx, r.id))
  for (const target of new Set(done.map((r) => r.target_uuid).filter((u): u is string => !!u))) {
    endAutoMuteIfClear(ctx, actor, target, done.find((r) => r.target_uuid === target)!.id)
  }
  return { updated: unique.filter((x) => doneIds.has(x)), skipped: unique.filter((x) => !doneIds.has(x)) }
}

export interface AdminUserModeration {
  uuid: string
  name: string | null
  mute: SanctionView | null
  sanctions: SanctionView[]
  reportsAgainst: { total: number, open: number, actioned: number, dismissed: number }
  reportsFiled: { total: number, open: number, actioned: number, dismissed: number, lowTrust: boolean }
}

export function adminUserModeration(ctx: AppContext, uuid: string): AdminUserModeration {
  const c = (col: 'target_uuid' | 'reporter_uuid') => one<{ total: number, open: number, actioned: number, dismissed: number }>(
    ctx.db,
    `SELECT COUNT(*) AS total, COALESCE(SUM(status <> 'resolved'), 0) AS open,
       COALESCE(SUM(outcome = 'actioned'), 0) AS actioned, COALESCE(SUM(outcome = 'dismissed'), 0) AS dismissed
     FROM chat_reports WHERE ${col} = ?`,
    uuid,
  )!
  const m = activeMute(ctx, uuid)
  return {
    uuid,
    name: getUser(ctx, uuid)?.name ?? null,
    mute: m ? sanctionView(ctx, m) : null,
    sanctions: chatSanctions(ctx, uuid, 50),
    reportsAgainst: c('target_uuid'),
    reportsFiled: { ...c('reporter_uuid'), lowTrust: reporterTrust(ctx, uuid).low },
  }
}

export function reportStats(ctx: AppContext): { open: number, inReview: number, resolved: number, activeMutes: number } {
  const n = (sql: string, ...p: (string | number)[]) => one<{ n: number }>(ctx.db, sql, ...p)!.n
  return {
    open: n("SELECT COUNT(*) AS n FROM chat_reports WHERE status = 'open'"),
    inReview: n("SELECT COUNT(*) AS n FROM chat_reports WHERE status = 'in_review'"),
    resolved: n("SELECT COUNT(*) AS n FROM chat_reports WHERE status = 'resolved'"),
    activeMutes: n(
      "SELECT COUNT(DISTINCT uuid) AS n FROM sanctions WHERE kind = 'chat_mute' AND lifted_at IS NULL AND (expires_at IS NULL OR expires_at > ?)",
      ctx.now(),
    ),
  }
}

// ---------------------------------------------------------------- Aufbewahrung, Kontolöschung, Schlüsseltausch

/**
 * Aufbewahrung: Beweise (Schnappschuss, Notizen, Bildkopien) erledigter Meldungen nach
 * `reportEvidenceRetentionMs` löschen; die Meldung selbst (ohne Inhalte) nach `reportRetentionMs`.
 * Strafen (alle Arten) 2 Jahre nach ihrem Ende (§22.10).
 */
export function sweepModeration(ctx: AppContext): void {
  const lim = ctx.config.limits
  const t = ctx.now()
  for (const r of all<{ id: string }>(
    ctx.db,
    "SELECT id FROM chat_reports WHERE status = 'resolved' AND evidence_purged_at IS NULL AND resolved_at < ? LIMIT 500",
    t - lim.reportEvidenceRetentionMs,
  )) {
    removeEvidenceFiles(ctx, r.id)
    run(ctx.db, 'DELETE FROM chat_report_notes WHERE report_id = ?', r.id)
    run(ctx.db, 'UPDATE chat_reports SET evidence = NULL, note = NULL, evidence_purged_at = ? WHERE id = ?', t, r.id)
  }
  for (const r of all<{ id: string }>(
    ctx.db, "SELECT id FROM chat_reports WHERE status = 'resolved' AND resolved_at < ? LIMIT 500", t - lim.reportRetentionMs,
  )) {
    removeEvidenceFiles(ctx, r.id)
    run(ctx.db, 'DELETE FROM chat_reports WHERE id = ?', r.id)
    run(ctx.db, 'DELETE FROM admin_log WHERE ref = ?', r.id)
  }
  // Strafen, Notizen, Namen-Verlauf, Audit-Log: 2 Jahre (sanctions.ts).
  sweepSanctions(ctx)
}

/**
 * Kontolöschung: Verwarnungen und beendete Stummschaltungen weg; eine **aktive** Stummschaltung
 * bleibt (sonst ließe sie sich per Löschen umgehen – wie Sperren). Eigene Meldungen verlieren den
 * Melder (FK), Meldungen gegen das Konto bleiben mit ihren Beweisen bis zum Ablauf der Aufbewahrung.
 */
export function purgeModeration(ctx: AppContext, uuid: string): void {
  purgeSanctions(ctx, uuid)
}

export function rotateReportKeys(ctx: AppContext, max: number): number {
  const prefix = Buffer.concat([Buffer.from([1, ctx.cipher.activeId.length]), Buffer.from(ctx.cipher.activeId, 'ascii')])
  let n = 0
  for (const r of all<{ id: string, evidence: Uint8Array | null, note: Uint8Array | null }>(
    ctx.db,
    `SELECT id, evidence, note FROM chat_reports
     WHERE (evidence IS NOT NULL AND substr(evidence, 1, ?) <> ?) OR (note IS NOT NULL AND substr(note, 1, ?) <> ?) LIMIT ?`,
    prefix.length, prefix, prefix.length, prefix, max,
  )) {
    try {
      const ev = r.evidence ? ctx.cipher.encrypt(ctx.cipher.decrypt(r.evidence, `rep:${r.id}`), `rep:${r.id}`) : null
      const note = r.note ? ctx.cipher.encrypt(ctx.cipher.decrypt(r.note, `rnote:${r.id}`), `rnote:${r.id}`) : null
      run(ctx.db, 'UPDATE chat_reports SET evidence = ?, note = ? WHERE id = ?', ev, note, r.id)
      n++
    } catch (err) {
      console.error(`[trs-api] could not re-encrypt report ${r.id}`, (err as Error).message)
    }
  }
  for (const r of all<{ id: number, report_id: string, text: Uint8Array }>(
    ctx.db, 'SELECT id, report_id, text FROM chat_report_notes WHERE substr(text, 1, ?) <> ? LIMIT ?', prefix.length, prefix, max,
  )) {
    try {
      const aad = `rnotes:${r.report_id}:${r.id}`
      run(ctx.db, 'UPDATE chat_report_notes SET text = ? WHERE id = ?', ctx.cipher.encrypt(ctx.cipher.decrypt(r.text, aad), aad), r.id)
      n++
    } catch (err) {
      console.error(`[trs-api] could not re-encrypt report note ${r.id}`, (err as Error).message)
    }
  }
  return n
}
