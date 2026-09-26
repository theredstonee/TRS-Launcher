import { z } from 'zod'
// Relativ importiert, damit Tests die Datei ohne Nuxt laden können.
import { adminReportSummarySchema, auditEntrySchema } from './moderation'
import { appealStatuses, reasonCodes, sanctionKinds, systemReasonCodes, type SanctionKind } from './sanctions'

// Team-Bereich (Moderation v2, API §22): Schemas der gesäuberten Antworten aus
// dem Kern, Rechte je Rolle und die Dauer-Vorlagen. Unbekannte Felder fallen
// weg, fehlende bekommen harmlose Standardwerte – eine neuere API legt den
// Bereich so nicht lahm. Rechte prüft ohnehin der Server bei jeder Anfrage.

const str = (max = 4000) => z.string().max(max)
const opt = (max = 4000) => z.string().max(max).nullable().default(null)
const num = z.number().int().min(0).default(0)
const uuid = str(32)
/** Wer etwas getan hat: UUID, `api-key` oder `system` (dann ohne Namen). */
const actor = z.object({ uuid: str(40), name: opt(16) })
const optActor = actor.nullable().default(null)

export type StaffRole = 'admin' | 'moderator'

export const durationPresets = ['1h', '6h', '1d', '3d', '7d', '30d', 'permanent', 'custom'] as const
export type DurationPreset = (typeof durationPresets)[number]
export const durationMinutes: Record<Exclude<DurationPreset, 'permanent' | 'custom'>, number> = {
  '1h': 60,
  '6h': 360,
  '1d': 1440,
  '3d': 4320,
  '7d': 10_080,
  '30d': 43_200,
}

// --- Rechte (Spiegel von lib/staff.ts, nur für die Oberfläche) ------------------------------

export interface StaffLimits {
  kinds: SanctionKind[]
  /** Höchstdauer in Minuten (`null` = unbegrenzt). */
  maxMinutes: number | null
  maxWarnMinutes: number | null
  permanent: boolean
}

export const limitsByRole: Record<StaffRole, StaffLimits> = {
  admin: { kinds: [...sanctionKinds], maxMinutes: null, maxWarnMinutes: null, permanent: true },
  moderator: { kinds: sanctionKinds.filter((k) => k !== 'account_ban'), maxMinutes: 10_080, maxWarnMinutes: 43_200, permanent: false },
}

/** Was nur Admins dürfen (Moderatoren sehen es ausgegraut oder gar nicht). */
export const adminOnly = ['roles', 'codes', 'wordfilter', 'uploads.delete', 'grants', 'sanctions.modifyAdmin'] as const
export type AdminOnly = (typeof adminOnly)[number]

// --- Strafen --------------------------------------------------------------------------------

const kind = z.enum(sanctionKinds)
const reasonCode = z.enum([...reasonCodes, ...systemReasonCodes]).catch('other')

export const adminAppealViewSchema = z.object({
  id: z.number().int().positive(),
  status: z.enum(appealStatuses),
  text: str(1000).default(''),
  createdAt: str(40),
  decidedAt: opt(40),
  decidedBy: optActor,
  response: opt(1000),
})

export const sanctionChangeSchema = z.object({
  at: str(40),
  actor,
  action: z.enum(['shorten', 'extend', 'lift']),
  oldEndsAt: opt(40),
  newEndsAt: opt(40),
  reason: str(500).default(''),
})

export const adminSanctionSchema = z.object({
  id: z.number().int().positive(),
  player: z.object({ uuid, name: opt(16) }),
  kind,
  reasonCode,
  reason: opt(500),
  note: opt(2000),
  reportId: opt(32),
  auto: z.enum(['reports', 'spam']).nullable().default(null),
  createdAt: str(40),
  createdBy: actor,
  createdRole: z.enum(['admin', 'moderator', 'system']).default('admin'),
  endsAt: opt(40),
  permanent: z.boolean().default(false),
  status: z.enum(['active', 'expired', 'lifted']),
  liftedAt: opt(40),
  liftedBy: optActor,
  liftReason: opt(500),
  changes: z.array(sanctionChangeSchema).default([]),
  appeal: adminAppealViewSchema.nullable().default(null),
  migrated: z.boolean().default(false),
})

export const adminAppealSchema = adminAppealViewSchema.extend({ sanction: adminSanctionSchema })

export const sanctionPageSchema = z.object({ sanctions: z.array(adminSanctionSchema), nextCursor: opt(256) })
export const sanctionEnvelopeSchema = z.object({ sanction: adminSanctionSchema })
export const appealPageSchema = z.object({ appeals: z.array(adminAppealSchema), nextCursor: opt(256), open: num })
export const appealEnvelopeSchema = z.object({ appeal: adminAppealSchema })

// --- Spieler --------------------------------------------------------------------------------

const role = z.enum(['admin', 'moderator']).nullable().default(null)
const counts = z.object({ total: num, open: num, actioned: num, dismissed: num }).default({ total: 0, open: 0, actioned: 0, dismissed: 0 })

export const playerListItemSchema = z.object({
  uuid,
  name: str(16).default(''),
  role,
  online: z.boolean().default(false),
  createdAt: opt(40),
  lastLoginAt: opt(40),
  activeSanctions: z.array(kind.catch('warn')).default([]),
  openReports: num,
})
export const playerPageSchema = z.object({ players: z.array(playerListItemSchema), nextCursor: opt(256) })

const limits = z.object({
  kinds: z.array(z.string()).default([]),
  maxMinutes: z.number().int().nullable().default(null),
  maxWarnMinutes: z.number().int().nullable().default(null),
  permanent: z.boolean().default(false),
})

const fileUpload = z.object({
  id: str(40),
  name: str(64).default(''),
  status: str(20).default('approved'),
  kind: str(20).default('upload'),
  slot: opt(20),
  createdAt: opt(40),
  reports: num,
  source: z.enum(['upload', 'code', 'admin']).catch('upload'),
})

export const adminRoomSchema = z.object({
  id: str(24),
  code: str(12).default(''),
  name: str(64).default(''),
  host: z.object({ uuid, name: str(16).default('') }),
  mcVersion: str(24).default(''),
  loader: str(16).default('vanilla'),
  maxPlayers: num,
  players: num,
  open: z.boolean().default(true),
  visibility: str(16).default('friends'),
  members: z.object({ accepted: num, invited: num, requested: num, banned: num }).default({ accepted: 0, invited: 0, requested: 0, banned: 0 }),
  createdAt: str(40),
  heartbeatAt: opt(40),
})
export const roomListSchema = z.object({ rooms: z.array(adminRoomSchema) })

export const playerNoteSchema = z.object({ id: z.number().int().positive(), at: str(40), actor, text: str(2000), deletable: z.boolean().default(false) })
export const notesEnvelopeSchema = z.object({ notes: z.array(playerNoteSchema) })

export const playerFileSchema = z.object({
  player: z.object({
    uuid,
    name: opt(16),
    known: z.boolean().default(false),
    role,
    online: z.boolean().default(false),
    firstLoginAt: opt(40),
    lastLoginAt: opt(40),
    friends: num,
    sessions: num,
    banned: z.boolean().default(false),
  }),
  names: z.array(z.object({ name: str(16), firstSeen: str(40), lastSeen: str(40) })).default([]),
  sanctions: z.array(adminSanctionSchema).default([]),
  warnings: z.object({ total: num, active: num }).default({ total: 0, active: 0 }),
  reports: z
    .object({
      against: z.object({ counts, recent: z.array(adminReportSummarySchema).default([]) }),
      filed: z.object({ counts, recent: z.array(adminReportSummarySchema).default([]) }),
      reporterScore: z
        .object({ actioned: num, dismissed: num, low: z.boolean().default(false), score: z.number().nullable().default(null) })
        .default({ actioned: 0, dismissed: 0, low: false, score: null }),
    })
    .default({
      against: { counts: { total: 0, open: 0, actioned: 0, dismissed: 0 }, recent: [] },
      filed: { counts: { total: 0, open: 0, actioned: 0, dismissed: 0 }, recent: [] },
      reporterScore: { actioned: 0, dismissed: 0, low: false, score: null },
    }),
  capes: z.array(fileUpload).default([]),
  cosmetics: z.array(fileUpload).default([]),
  worlds: z.array(adminRoomSchema).default([]),
  notes: z.array(playerNoteSchema).default([]),
  can: z
    .object({ sanction: z.boolean().default(false), reason: z.enum(['self', 'admin', 'staff']).nullable().default(null), limits: limits.nullable().default(null) })
    .default({ sanction: false, reason: null, limits: null }),
})
export const playerFileEnvelopeSchema = z.object({ file: playerFileSchema })

// --- Übersicht, Suche, Rollen, Uploads -------------------------------------------------------

const series = z.array(z.number().int().min(0)).default([])

export const dashboardSchema = z.object({
  reports: z.object({ open: num, inReview: num, highPriority: num, oldestOpenAt: opt(40) }).default({ open: 0, inReview: 0, highPriority: 0, oldestOpenAt: null }),
  appeals: z.object({ open: num, oldestOpenAt: opt(40) }).default({ open: 0, oldestOpenAt: null }),
  sanctions: z.record(z.string(), z.number().int().min(0)).default({}),
  uploads: z
    .object({ capesPending: num, capesReported: num, cosmeticsPending: num, cosmeticsReported: num })
    .default({ capesPending: 0, capesReported: 0, cosmeticsPending: 0, cosmeticsReported: 0 }),
  users: z
    .object({ total: num, new24h: num, new7d: num, active24h: num, active7d: num, online: num })
    .default({ total: 0, new24h: 0, new7d: 0, active24h: 0, active7d: 0, online: 0 }),
  hosting: z.object({ openRooms: num, players: num }).default({ openRooms: 0, players: 0 }),
  chat: z.object({ messages24h: num }).default({ messages24h: 0 }),
  series: z
    .object({ days: z.array(str(10)).default([]), newUsers: series, messages: series, reports: series, sanctions: series })
    .default({ days: [], newUsers: [], messages: [], reports: [], sanctions: [] }),
  server: z
    .object({
      version: str(40).default(''),
      node: str(40).default(''),
      uptimeSec: num,
      startedAt: opt(40),
      dbBytes: num,
      disk: z.object({ freeBytes: num, totalBytes: num }).nullable().default(null),
    })
    .nullable()
    .default(null),
  recentAudit: z.array(auditEntrySchema).default([]),
})

const owner = z.object({ uuid, name: opt(16) }).nullable().default(null)
export const searchResultSchema = z.object({
  players: z.array(z.object({ uuid, name: opt(16), role, matched: opt(16) })).default([]),
  reports: z.array(adminReportSummarySchema).default([]),
  capes: z.array(z.object({ id: str(40), name: str(64).default(''), kind: str(20).default(''), status: str(20).default(''), owner })).default([]),
  cosmetics: z
    .array(z.object({ id: str(40), name: str(64).default(''), kind: str(20).default(''), status: str(20).default(''), slot: str(20).default(''), owner }))
    .default([]),
  sanctions: z.array(adminSanctionSchema).default([]),
})

export const roleViewSchema = z.object({
  uuid,
  name: opt(16),
  role: z.enum(['admin', 'moderator']),
  source: z.enum(['env', 'db']).catch('db'),
  grantedAt: opt(40),
  grantedBy: optActor,
  note: opt(200),
})
export const rolesSchema = z.object({ roles: z.array(roleViewSchema) })

export const bulkResultSchema = z.object({ updated: z.array(str(40)).default([]), skipped: z.array(str(40)).default([]) })

export const adminCosmeticSchema = z.object({
  id: str(40),
  name: str(64).default(''),
  slot: str(20).default(''),
  kind: str(20).default('upload'),
  status: z.enum(['pending', 'approved', 'rejected']).catch('pending'),
  owner: z.object({ uuid, name: str(16).default('') }).nullable().default(null),
  createdAt: opt(40),
  reviewedAt: opt(40),
  reviewedBy: opt(80),
  rejectReason: opt(200),
  reports: z.object({ count: num, reasons: z.record(z.string(), z.number()).default({}) }).default({ count: 0, reasons: {} }),
  /** Vorschau der Textur als Data-URL (lädt der Kern). */
  preview: z.string().startsWith('data:image/png;base64,').nullable().catch(null).default(null),
})
export const cosmeticPageSchema = z.object({ cosmetics: z.array(adminCosmeticSchema), nextCursor: opt(256) })

export type AdminSanction = z.infer<typeof adminSanctionSchema>
export type AdminAppeal = z.infer<typeof adminAppealSchema>
export type SanctionChange = z.infer<typeof sanctionChangeSchema>
export type PlayerListItem = z.infer<typeof playerListItemSchema>
export type PlayerFile = z.infer<typeof playerFileSchema>
export type PlayerNote = z.infer<typeof playerNoteSchema>
export type AdminRoom = z.infer<typeof adminRoomSchema>
export type Dashboard = z.infer<typeof dashboardSchema>
export type SearchResult = z.infer<typeof searchResultSchema>
export type RoleView = z.infer<typeof roleViewSchema>
export type BulkResult = z.infer<typeof bulkResultSchema>
export type AdminCosmetic = z.infer<typeof adminCosmeticSchema>

// --- Eingaben -------------------------------------------------------------------------------

export interface SanctionQuery {
  status?: 'active' | 'expired' | 'lifted' | 'all'
  kind?: SanctionKind
  uuid?: string
  actor?: string
  from?: string
  to?: string
  sort?: 'newest' | 'oldest'
  cursor?: string
  limit?: number
}

export interface NewSanctionInput {
  uuid: string
  kind: SanctionKind
  duration: DurationPreset
  minutes?: number
  reasonCode: (typeof reasonCodes)[number]
  reason?: string
  note?: string
  reportId?: string
}

export interface AppealDecisionInput {
  decision: 'lift' | 'shorten' | 'uphold'
  response: string
  endsAt?: string
}

export interface PlayerQuery {
  q?: string
  status?: 'all' | 'sanctioned' | 'banned' | 'staff' | 'reported'
  sort?: 'last_login' | 'created'
  cursor?: string
  limit?: number
}

export interface UploadQuery {
  status?: 'pending' | 'approved' | 'rejected' | 'reported'
  owner?: string
  q?: string
  sort?: 'newest' | 'oldest'
  cursor?: string
  limit?: number
}

export type BulkTarget = 'reports' | 'capes' | 'cosmetics'

// --- Entwurf einer Strafe --------------------------------------------------------------------

export interface SanctionDraft {
  kind: SanctionKind
  duration: DurationPreset
  customValue: number
  customUnit: 'minutes' | 'hours' | 'days'
  reasonCode: (typeof reasonCodes)[number] | ''
  reason: string
  note: string
}

export function emptyDraft(kind: SanctionKind = 'warn'): SanctionDraft {
  return { kind, duration: kind === 'warn' ? '30d' : '1d', customValue: 2, customUnit: 'days', reasonCode: '', reason: '', note: '' }
}

const unitMinutes = { minutes: 1, hours: 60, days: 1440 } as const

/** Minuten der Auswahl (`null` = dauerhaft, `NaN` = ungültig). */
export function draftMinutes(d: Pick<SanctionDraft, 'duration' | 'customValue' | 'customUnit'>): number | null {
  if (d.duration === 'permanent') return null
  if (d.duration === 'custom') {
    const m = Math.round(Number(d.customValue) * unitMinutes[d.customUnit])
    return Number.isFinite(m) ? m : Number.NaN
  }
  return durationMinutes[d.duration]
}

export function maxMinutesFor(kind: SanctionKind, limits: StaffLimits): number | null {
  return kind === 'warn' ? limits.maxWarnMinutes : limits.maxMinutes
}

/** Darf diese Rolle die Dauer vergeben? */
export function durationAllowed(preset: DurationPreset, kind: SanctionKind, limits: StaffLimits): boolean {
  if (preset === 'permanent') return limits.permanent
  if (preset === 'custom') return true
  const max = maxMinutesFor(kind, limits)
  return max === null || durationMinutes[preset] <= max
}

/** Fehler im Entwurf (Schlüssel-Suffix unter `admin.sanctionForm.errors`) oder `null`. */
export function draftProblem(d: SanctionDraft, limits: StaffLimits): 'kind' | 'duration' | 'custom' | 'reasonCode' | 'reason' | 'note' | null {
  if (!limits.kinds.includes(d.kind)) return 'kind'
  if (!durationAllowed(d.duration, d.kind, limits)) return 'duration'
  if (d.duration === 'custom') {
    const m = draftMinutes(d)
    const max = maxMinutesFor(d.kind, limits)
    if (m === null || !Number.isFinite(m) || m < 5 || m > 5_256_000 || (max !== null && m > max)) return 'custom'
  }
  if (!d.reasonCode) return 'reasonCode'
  if ([...d.reason.trim()].length > 500 || /[\n\r]/.test(d.reason)) return 'reason'
  if ([...d.note.trim()].length > 2000) return 'note'
  return null
}

/** Entwurf → Body für `admin_sanction_create`. */
export function draftToInput(uuid: string, d: SanctionDraft, reportId?: string): NewSanctionInput {
  const input: NewSanctionInput = { uuid, kind: d.kind, duration: d.duration, reasonCode: d.reasonCode as NewSanctionInput['reasonCode'] }
  if (d.duration === 'custom') input.minutes = draftMinutes(d) ?? undefined
  if (d.reason.trim()) input.reason = d.reason.trim()
  if (d.note.trim()) input.note = d.note.trim()
  if (reportId) input.reportId = reportId
  return input
}

/** Voraussichtliches Ende einer neuen Strafe (für die Bestätigung). */
export function draftEnd(d: SanctionDraft, now = Date.now()): Date | null {
  const m = draftMinutes(d)
  return m === null || !Number.isFinite(m) ? null : new Date(now + m * 60_000)
}

/** Ort im Team-Bereich, an dem ein Suchtreffer landet. */
export function playerPath(uuid: string): string {
  return `/admin/players/${uuid}`
}
