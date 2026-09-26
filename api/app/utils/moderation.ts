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

// ---------------------------------------------------------------- Moderation v2 (API.md §22)

export type SanctionKind = 'warn' | 'chat_mute' | 'social_ban' | 'upload_ban' | 'hosting_ban' | 'account_ban'
export const SANCTION_KINDS: SanctionKind[] = ['warn', 'chat_mute', 'social_ban', 'upload_ban', 'hosting_ban', 'account_ban']
export const REASON_CODES = [
  'spam', 'insult_hate', 'harassment', 'inappropriate_content', 'inappropriate_name', 'scam_phishing',
  'impersonation', 'copyright', 'cheating', 'ban_evasion', 'other',
] as const
export type ReasonCode = (typeof REASON_CODES)[number] | 'auto_spam' | 'auto_reports' | 'legacy'
export type DurationPreset = '1h' | '6h' | '1d' | '3d' | '7d' | '30d' | 'permanent' | 'custom'
export const DURATION_PRESETS: DurationPreset[] = ['1h', '6h', '1d', '3d', '7d', '30d', 'permanent', 'custom']
export const DURATION_MINUTES: Record<string, number> = { '1h': 60, '6h': 360, '1d': 1440, '3d': 4320, '7d': 10080, '30d': 43200 }
export type SanctionStatus = 'active' | 'expired' | 'lifted'
export type AppealStatus = 'open' | 'lifted' | 'shortened' | 'upheld'

export interface ActorRef {
  uuid: string
  name: string | null
}

export interface AdminSanction {
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
  createdRole: 'admin' | 'moderator' | 'system'
  endsAt: string | null
  permanent: boolean
  status: SanctionStatus
  liftedAt: string | null
  liftedBy: ActorRef | null
  liftReason: string | null
  changes: { at: string, actor: ActorRef, action: 'shorten' | 'extend' | 'lift', oldEndsAt: string | null, newEndsAt: string | null, reason: string }[]
  appeal: { id: number, status: AppealStatus, createdAt: string, decidedAt: string | null, response: string | null, text: string, decidedBy: ActorRef | null } | null
  migrated: boolean
}

export interface AdminAppeal {
  id: number
  status: AppealStatus
  text: string
  createdAt: string
  decidedAt: string | null
  decidedBy: ActorRef | null
  response: string | null
  sanction: AdminSanction
}

export interface ReportSummaryV2 extends ReportSummary {
  priority?: 'high' | 'normal'
}

export interface AdminRoom {
  id: string
  code: string
  name: string
  host: { uuid: string, name: string }
  mcVersion: string
  loader: string
  maxPlayers: number
  players: number
  open: boolean
  visibility: 'friends' | 'invited'
  members: { accepted: number, invited: number, requested: number, banned: number }
  createdAt: string
  heartbeatAt: string
}

export interface PlayerFile {
  player: {
    uuid: string
    name: string | null
    known: boolean
    role: 'admin' | 'moderator' | null
    online: boolean
    firstLoginAt: string | null
    lastLoginAt: string | null
    friends: number
    sessions: number
    banned: boolean
  }
  names: { name: string, firstSeen: string, lastSeen: string }[]
  sanctions: AdminSanction[]
  warnings: { total: number, active: number }
  reports: {
    against: { counts: { total: number, open: number, actioned: number, dismissed: number }, recent: ReportSummaryV2[] }
    filed: { counts: { total: number, open: number, actioned: number, dismissed: number }, recent: ReportSummaryV2[] }
    reporterScore: { actioned: number, dismissed: number, low: boolean, score: number | null }
  }
  capes: { id: string, name: string, status: string, url: string, scale: number, frames: number, frameTimeMs: number | null, createdAt: string, reports: number, source: string }[]
  cosmetics: { id: string, name: string, status: string, slot: string, createdAt: string | null, reports: number, source: string }[]
  worlds: AdminRoom[]
  notes: { id: number, at: string, actor: ActorRef, text: string, deletable: boolean }[]
  can: { sanction: boolean, reason: string | null, limits: { kinds: string[], maxMinutes: number | null, maxWarnMinutes: number | null, permanent: boolean } }
}

export interface AuditRow {
  id: number
  at: string
  actor: string
  actorName: string | null
  action: string
  target: string | null
  targetName: string | null
  detail: string | null
  ref: string | null
}

export interface DashboardData {
  reports: { open: number, inReview: number, highPriority: number, oldestOpenAt: string | null }
  appeals: { open: number, oldestOpenAt: string | null }
  sanctions: Record<SanctionKind, number>
  uploads: { capesPending: number, capesReported: number, cosmeticsPending: number, cosmeticsReported: number }
  users: { total: number, new24h: number, new7d: number, active24h: number, active7d: number, online: number }
  hosting: { openRooms: number, players: number }
  chat: { messages24h: number }
  series: { days: string[], newUsers: number[], messages: number[], reports: number[], sanctions: number[] }
  server: { version: string, node: string, uptimeSec: number, startedAt: string, dbBytes: number, disk: { freeBytes: number, totalBytes: number } | null }
  recentAudit: AuditRow[]
}

export interface SearchResult {
  players: { uuid: string, name: string | null, role: string | null, matched: string | null }[]
  reports: ReportSummaryV2[]
  capes: { id: string, name: string, kind: string, status: string, owner: { uuid: string, name: string | null } | null }[]
  cosmetics: { id: string, name: string, kind: string, status: string, slot: string, owner: { uuid: string, name: string | null } | null }[]
  sanctions: AdminSanction[]
}

/** Farbklasse je Strafart (Badges, s. admin.css). */
export function kindTone(kind: SanctionKind): string {
  return `kind-${kind}`
}

/** Farbklasse je Status (aktiv/abgelaufen/aufgehoben). */
export function statusTone(status: SanctionStatus): string {
  return status === 'active' ? 'tone-danger' : status === 'lifted' ? 'tone-muted' : 'tone-muted'
}

/** Bytes lesbar (kB/MB/GB) in der Seitensprache. */
export function humanBytes(n: number, lang: string): string {
  const units = ['B', 'kB', 'MB', 'GB', 'TB']
  let v = n
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${new Intl.NumberFormat(lang === 'en' ? 'en-GB' : lang, { maximumFractionDigits: v < 10 && i > 0 ? 1 : 0 }).format(v)} ${units[i]}`
}

/** Dauer in Sekunden, kurz: 3 d 4 h / 5 h 12 min / 7 min. */
export function humanDuration(sec: number): string {
  const d = Math.floor(sec / 86400)
  const h = Math.floor((sec % 86400) / 3600)
  const m = Math.floor((sec % 3600) / 60)
  if (d > 0) return `${d} d ${h} h`
  if (h > 0) return `${h} h ${m} min`
  return `${m} min`
}

/** ISO → Wert für `<input type="datetime-local">` (lokale Zeit). */
export function toLocalInput(iso: string | null | undefined): string {
  const d = iso ? new Date(iso) : new Date()
  const p = (x: number) => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}

/** Wert aus `<input type="datetime-local">` → ISO (oder `null`). */
export function fromLocalInput(v: string): string | null {
  if (!v) return null
  const d = new Date(v)
  return Number.isNaN(d.getTime()) ? null : d.toISOString()
}

/** Akteur lesbar: Name, sonst System/API-Schlüssel, sonst gekürzte UUID. */
export function actorLabel(a: { uuid: string, name: string | null } | null | undefined, t: { system: string, apiKey: string }): string {
  if (!a) return '–'
  if (a.name) return a.name
  if (a.uuid === 'system') return t.system
  if (a.uuid === 'api-key') return t.apiKey
  return `${a.uuid.slice(0, 8)}…`
}

/** Entwurf einer Strafe im Formular (SanctionForm). */
export interface SanctionDraft {
  kind: SanctionKind
  duration: DurationPreset
  customValue: number
  customUnit: 'minutes' | 'hours' | 'days'
  reasonCode: string
  reason: string
  note: string
}

export function newSanctionDraft(kind: SanctionKind = 'chat_mute', reasonCode = ''): SanctionDraft {
  return { kind, duration: '1d', customValue: 2, customUnit: 'hours', reasonCode, reason: '', note: '' }
}

/** Entwurf → Felder für `POST /v1/admin/sanctions` bzw. die Meldungs-Aktion `sanction`. */
export function draftBody(d: SanctionDraft): { kind: SanctionKind, duration: DurationPreset, minutes?: number, reasonCode: string, reason?: string, note?: string } {
  const unit = { minutes: 1, hours: 60, days: 1440 }[d.customUnit]
  return {
    kind: d.kind,
    duration: d.duration,
    ...(d.duration === 'custom' ? { minutes: Math.round(d.customValue * unit) } : {}),
    reasonCode: d.reasonCode,
    ...(d.reason.trim() ? { reason: d.reason.trim().slice(0, 500) } : {}),
    ...(d.note.trim() ? { note: d.note.trim().slice(0, 2000) } : {}),
  }
}
