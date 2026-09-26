// Typen der Chat-Moderation (`/v1/admin/reports*`, `/v1/admin/moderation/*`, `/v1/admin/chat/word-filter`).

export type ReportKind = 'message' | 'image' | 'player' | 'group'
export type ReportStatus = 'open' | 'in_review' | 'resolved'
export type ReportOutcome = 'actioned' | 'dismissed'
export type ReportReason = 'insult_hate' | 'spam' | 'inappropriate' | 'scam_phishing' | 'harassment' | 'other'
export type ReportFilter = ReportStatus | 'active' | 'all'

export interface PlayerRef {
  uuid: string
  name: string
}

export interface ReportSummary {
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
  preview: string | null
  images: number
  lowTrust: boolean
  assignedTo: PlayerRef | null
  targetOpenReports: number
  createdAt: string
  updatedAt: string
  resolvedAt: string | null
  resolvedBy: string | null
  evidencePurged: boolean
}

export interface EvidenceMessage {
  id: string
  seq: number
  kind: 'text' | 'system'
  sender: PlayerRef | null
  text: string | null
  invite: { address: string, name: string | null } | null
  /** Weltkarte (Welt-Hosting); fehlt in älteren Beweisen. */
  world?: { roomId: string, name: string } | null
  system: { event: string, target: string | null, name: string | null } | null
  attachments: { id: string, width: number, height: number, mime: string }[]
  replyTo: string | null
  createdAt: string
  editedAt: string | null
  deleted: boolean
}

export interface Sanction {
  id: number
  kind: 'warn' | 'mute'
  reason: string | null
  reportId: string | null
  auto: 'reports' | 'spam' | null
  createdAt: string
  createdBy: string
  expiresAt: string | null
  liftedAt: string | null
  liftedBy: string | null
  active: boolean
}

export interface ReportDetail extends ReportSummary {
  note: string | null
  evidence: {
    capturedAt: string
    reporter: PlayerRef | null
    target: PlayerRef | null
    conversation: { id: string, kind: 'dm' | 'group', name: string | null, owner: string | null, members: PlayerRef[] } | null
    focus: string | null
    messages: EvidenceMessage[]
    images: { id: string, width: number, height: number, mime: string, path: string }[]
  } | null
  notes: { id: number, at: string, actor: string, actorName: string | null, text: string }[]
  audit: { at: string, actor: string, actorName: string | null, action: string, detail: string | null }[]
  targetModeration: {
    mute: Sanction | null
    sanctions: Sanction[]
    reports: { total: number, open: number, actioned: number, dismissed: number }
  } | null
  reporterStats: { actioned: number, dismissed: number, low: boolean, open: number } | null
  related: ReportSummary[]
}

export interface ModReportList {
  reports: ReportSummary[]
  nextCursor: string | null
  counts: Record<ReportStatus, number>
}

export interface FilterWord {
  id: number
  word: string
  mode: 'word' | 'contains'
  action: 'block' | 'mask'
  createdAt: string
  createdBy: string
}

export type ReportAction = 'delete_message' | 'warn' | 'mute' | 'ban' | 'dismiss' | 'resolve'

/** Datum + Uhrzeit (kurz) in der Seitensprache. */
export function dateTime(iso: string | null | undefined, lang: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return new Intl.DateTimeFormat(lang === 'en' ? 'en-GB' : lang, { dateStyle: 'medium', timeStyle: 'short' }).format(d)
}
