import { z } from 'zod'
// Relativ importiert, damit Tests die Datei ohne Nuxt laden können.
import { intlLocale } from './i18n'

// Chat-Moderation für Admins (API §20.5): Schemas für die gesäuberten Antworten
// aus dem Kern. Die Antworten sind groß – unbekannte Felder werden verworfen,
// fehlende bekommen harmlose Standardwerte, damit eine neuere API die Seite
// nicht lahmlegt.

const str = (max = 4000) => z.string().max(max)
const opt = (max = 4000) => z.string().max(max).nullable().default(null)
const num = z.number().int().min(0).default(0)
const player = z.object({ uuid: str(32), name: str(16) })
const optPlayer = player.nullable().default(null)

export const reportFilters = ['active', 'open', 'in_review', 'resolved', 'all'] as const
export type ReportFilter = (typeof reportFilters)[number]
export const reportActions = ['delete_message', 'warn', 'mute', 'ban', 'dismiss', 'resolve'] as const
export type ReportActionId = (typeof reportActions)[number]

export const adminReportSummarySchema = z.object({
  id: z.string().regex(/^r[0-9a-f]{16}$/),
  kind: z.enum(['message', 'image', 'player', 'group']),
  reason: str(32),
  status: z.enum(['open', 'in_review', 'resolved']),
  outcome: z.enum(['actioned', 'dismissed']).nullable().default(null),
  reporter: optPlayer,
  target: optPlayer,
  conversationId: opt(32),
  messageId: opt(32),
  attachmentId: opt(32),
  preview: opt(400),
  images: num,
  lowTrust: z.boolean().default(false),
  assignedTo: optPlayer,
  targetOpenReports: num,
  createdAt: str(40),
  updatedAt: opt(40),
  resolvedAt: opt(40),
  resolvedBy: opt(80),
  evidencePurged: z.boolean().default(false),
})

export const sanctionSchema = z.object({
  id: z.number().int(),
  kind: z.enum(['warn', 'mute']),
  reason: opt(200),
  reportId: opt(32),
  auto: z.enum(['reports', 'spam']).nullable().default(null),
  createdAt: str(40),
  createdBy: opt(80),
  expiresAt: opt(40),
  liftedAt: opt(40),
  liftedBy: opt(80),
  active: z.boolean().default(false),
})

const counts = z.object({ total: num, open: num, actioned: num, dismissed: num })

export const evidenceMessageSchema = z.object({
  id: str(32),
  seq: num,
  kind: z.enum(['text', 'system']).default('text'),
  sender: optPlayer,
  text: opt(),
  invite: z.object({ address: str(261), name: opt(32) }).nullable().default(null),
  system: z.object({ event: str(32), target: z.union([str(32), player]).nullable().default(null), name: opt(32) }).nullable().default(null),
  attachments: z.array(z.object({ id: str(32), width: num, height: num, mime: str(20) })).default([]),
  replyTo: z.union([str(32), z.object({ id: str(32) }).passthrough()]).nullable().default(null),
  createdAt: str(40),
  editedAt: opt(40),
  deleted: z.boolean().default(false),
})

export const adminReportDetailSchema = adminReportSummarySchema.extend({
  note: opt(),
  evidence: z
    .object({
      capturedAt: str(40),
      reporter: optPlayer,
      target: optPlayer,
      conversation: z
        .object({ id: str(32), kind: z.enum(['dm', 'group']), name: opt(32), owner: opt(32), members: z.array(player).default([]) })
        .nullable()
        .default(null),
      focus: opt(32),
      messages: z.array(evidenceMessageSchema).default([]),
      images: z.array(z.object({ id: str(32), width: num, height: num, mime: str(20) })).default([]),
    })
    .nullable()
    .default(null),
  notes: z.array(z.object({ id: z.number().int(), at: str(40), actor: str(80), actorName: opt(16), text: str() })).default([]),
  audit: z.array(z.object({ at: str(40), actor: str(80), actorName: opt(16), action: str(64), detail: opt() })).default([]),
  targetModeration: z
    .object({ mute: sanctionSchema.nullable().default(null), sanctions: z.array(sanctionSchema).default([]), reports: counts })
    .nullable()
    .default(null),
  reporterStats: z.object({ actioned: num, dismissed: num, low: z.boolean().default(false), open: num }).nullable().default(null),
  related: z.array(adminReportSummarySchema).default([]),
})

export const adminReportListSchema = z.object({
  reports: z.array(adminReportSummarySchema),
  nextCursor: opt(256),
  counts: z.object({ open: num, in_review: num, resolved: num }).default({ open: 0, in_review: 0, resolved: 0 }),
})
export const adminReportEnvelopeSchema = z.object({ report: adminReportDetailSchema })

export const adminModerationSchema = z.object({
  uuid: str(32),
  name: opt(16),
  mute: sanctionSchema.nullable().default(null),
  sanctions: z.array(sanctionSchema).default([]),
  reportsAgainst: counts.default({ total: 0, open: 0, actioned: 0, dismissed: 0 }),
  reportsFiled: counts.extend({ lowTrust: z.boolean().default(false) }).default({ total: 0, open: 0, actioned: 0, dismissed: 0, lowTrust: false }),
})
export const adminModerationEnvelopeSchema = z.object({ moderation: adminModerationSchema })

export const filterWordSchema = z.object({
  id: z.number().int().positive(),
  word: str(64),
  mode: z.enum(['word', 'contains']),
  action: z.enum(['mask', 'block']),
  createdAt: str(40),
  createdBy: opt(80),
})
export const filterWordsSchema = z.object({ words: z.array(filterWordSchema) })
export const filterWordEnvelopeSchema = z.object({ word: filterWordSchema })

export const auditEntrySchema = z.object({
  id: z.number().int(),
  at: str(40),
  actor: str(80),
  actorName: opt(16),
  action: str(64),
  target: opt(32),
  targetName: opt(16),
  detail: opt(),
  ref: opt(32),
})
export const auditPageSchema = z.object({ entries: z.array(auditEntrySchema), nextBefore: z.number().int().nullable().default(null) })

export type AdminReportSummary = z.infer<typeof adminReportSummarySchema>
export type AdminReportDetail = z.infer<typeof adminReportDetailSchema>
export type AdminReportList = z.infer<typeof adminReportListSchema>
export type Sanction = z.infer<typeof sanctionSchema>
export type EvidenceMessage = z.infer<typeof evidenceMessageSchema>
export type AdminModeration = z.infer<typeof adminModerationSchema>
export type FilterWord = z.infer<typeof filterWordSchema>
export type AuditEntry = z.infer<typeof auditEntrySchema>

export interface ReportQuery {
  status?: ReportFilter
  kind?: string
  target?: string
  cursor?: string
  limit?: number
}

export interface ReportActionInput {
  action: ReportActionId
  reason?: string
  minutes?: number
  keepOpen?: boolean
  includeRelated?: boolean
}

export interface NewFilterWord {
  word: string
  mode?: 'word' | 'contains'
  action?: 'mask' | 'block'
}

export interface AuditQuery {
  ref?: string
  target?: string
  before?: number
  limit?: number
}

/** Dauern für „Stummschalten“ in Minuten (`null` = bis aufgehoben). */
export const muteDurations: (number | null)[] = [60, 1440, 10_080, 43_200, null]

/** Datum + Uhrzeit kurz in der eingestellten Sprache. */
export function dateTime(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return new Intl.DateTimeFormat(intlLocale(), { dateStyle: 'medium', timeStyle: 'short' }).format(d)
}

/** Body für eine Entscheidung – nur die Felder, die zur Aktion passen. */
export function actionBody(
  action: ReportActionId,
  form: { reason: string; minutes: number | null; keepOpen: boolean; includeRelated: boolean },
): ReportActionInput {
  const body: ReportActionInput = { action }
  const reason = form.reason.trim().slice(0, 200)
  if (reason && (action === 'warn' || action === 'mute' || action === 'ban')) body.reason = reason
  if (action === 'mute' && form.minutes !== null) body.minutes = form.minutes
  if (form.keepOpen && action !== 'dismiss' && action !== 'resolve') body.keepOpen = true
  if (form.includeRelated) body.includeRelated = true
  return body
}
