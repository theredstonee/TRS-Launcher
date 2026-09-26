import { z } from 'zod'
// Relativ importiert, damit Tests die Datei ohne Nuxt laden können.
import { formatDate, formatRelative } from './format'
import { hasKey, t, tKey } from './i18n'

// Eigene Strafen und Einspruch (API §22): Schemas, Texte und kleine Regeln, die
// Oberfläche und Store gemeinsam nutzen. Die Daten kommen gesäubert aus dem
// Kern (`trs_my_sanctions`, Live-Ereignisse, Fehlerparameter).

export const sanctionKinds = ['warn', 'chat_mute', 'social_ban', 'upload_ban', 'hosting_ban', 'account_ban'] as const
export type SanctionKind = (typeof sanctionKinds)[number]
/** Grund-Vorlagen, die das Team auswählen kann. */
export const reasonCodes = [
  'spam',
  'insult_hate',
  'harassment',
  'inappropriate_content',
  'inappropriate_name',
  'scam_phishing',
  'impersonation',
  'copyright',
  'cheating',
  'ban_evasion',
  'other',
] as const
/** Setzt nur das System (automatische Stummschaltung, übernommene Altstrafen). */
export const systemReasonCodes = ['auto_spam', 'auto_reports', 'legacy'] as const
export type ReasonCode = (typeof reasonCodes)[number] | (typeof systemReasonCodes)[number]
export const appealStatuses = ['open', 'lifted', 'shortened', 'upheld'] as const
export type AppealStatus = (typeof appealStatuses)[number]

/** Einspruch: 20 bis 1000 Zeichen, einmal je Strafe. */
export const APPEAL_MIN = 20
export const APPEAL_MAX = 1000

const time = z.string().max(40)

export const myAppealSchema = z.object({
  id: z.number().int().positive(),
  status: z.enum(appealStatuses),
  createdAt: time.nullable().default(null),
  decidedAt: time.nullable().default(null),
  response: z.string().max(APPEAL_MAX).nullable().default(null),
})

export const mySanctionSchema = z.object({
  id: z.number().int().positive(),
  kind: z.enum(sanctionKinds),
  reasonCode: z.enum([...reasonCodes, ...systemReasonCodes]).catch('other'),
  reason: z.string().max(500).nullable().default(null),
  startsAt: time.nullable().default(null),
  endsAt: time.nullable().default(null),
  status: z.enum(['active', 'expired', 'lifted']),
  liftedAt: time.nullable().default(null),
  appeal: myAppealSchema.nullable().default(null),
  appealable: z.boolean().default(false),
})

export const mySanctionsSchema = z.object({
  active: z.array(mySanctionSchema),
  past: z.array(mySanctionSchema),
})

export type MyAppeal = z.infer<typeof myAppealSchema>
export type MySanction = z.infer<typeof mySanctionSchema>
export type MySanctions = z.infer<typeof mySanctionsSchema>

/** Reihenfolge nach Schwere (für Banner und Sortierung). */
const severity: Record<SanctionKind, number> = {
  account_ban: 6,
  hosting_ban: 4,
  social_ban: 4,
  upload_ban: 3,
  chat_mute: 5,
  warn: 1,
}

export function bySeverity(a: Pick<MySanction, 'kind' | 'id'>, b: Pick<MySanction, 'kind' | 'id'>): number {
  return severity[b.kind] - severity[a.kind] || b.id - a.id
}

export function kindLabel(kind: SanctionKind): string {
  return t(`sanctions.kinds.${kind}`)
}

/** Was die Strafe sperrt – in einem Satz für Spieler. */
export function kindEffect(kind: SanctionKind): string {
  return t(`sanctions.effects.${kind}`)
}

export function reasonLabel(code: string): string {
  return hasKey(`sanctions.reasons.${code}`) ? tKey(`sanctions.reasons.${code}`) : t('sanctions.reasons.other')
}

/** Grund für Spieler: Vorlage + (falls vorhanden) öffentlicher Text. */
export function sanctionReasonText(s: Pick<MySanction, 'reasonCode' | 'reason'>): string {
  const label = reasonLabel(s.reasonCode)
  return s.reason ? t('sanctions.reasonWithText', { label, text: s.reason }) : label
}

/** Ist die Strafe (noch) wirksam? Abgelaufene Enden zählen nicht, auch wenn noch kein Ereignis kam. */
export function sanctionIsActive(s: Pick<MySanction, 'status' | 'endsAt'>, now = Date.now()): boolean {
  if (s.status !== 'active') return false
  if (!s.endsAt) return true
  const end = Date.parse(s.endsAt)
  return Number.isNaN(end) || end > now
}

/**
 * Ende in Worten: „dauerhaft“, „bis zur Prüfung“ (automatische Stummschaltung)
 * oder „endet in 5 Std. (26.09.2026, 18:00)“.
 */
export function endText(s: Pick<MySanction, 'endsAt' | 'reasonCode' | 'status' | 'liftedAt'>): string {
  if (s.status === 'lifted') return t('sanctions.end.lifted', { date: formatDate(s.liftedAt) })
  if (!s.endsAt) return s.reasonCode === 'auto_reports' ? t('sanctions.end.review') : t('sanctions.end.permanent')
  const date = formatDate(s.endsAt)
  if (s.status === 'expired' || Date.parse(s.endsAt) <= Date.now()) return t('sanctions.end.ended', { date })
  return t('sanctions.end.until', { relative: formatRelative(s.endsAt, true), date })
}

export function appealStatusText(status: AppealStatus): string {
  return t(`sanctions.appeal.status.${status}`)
}

/** Prüft einen Einspruchstext wie der Kern (Länge nach dem Trimmen). */
export function appealProblem(text: string): string | null {
  const n = [...text.replace(/\r/g, '').trim()].length
  if (n < APPEAL_MIN) return t('sanctions.appeal.tooShort', { min: APPEAL_MIN, n })
  if (n > APPEAL_MAX) return t('sanctions.appeal.tooLong', { max: APPEAL_MAX })
  return null
}

/** Welche Art ein Fehlercode meint, wenn die Details fehlen (ältere Server). */
const codeKinds: Record<string, SanctionKind> = { banned: 'account_ban', chat_muted: 'chat_mute' }

/** Fehlerparameter aus dem Kern (`sanctionKind`, `endsAt`, …) → Strafe (teilweise). */
export function sanctionFromParams(apiCode: string | undefined, params: Record<string, string> | undefined): MySanction | null {
  const kind = params?.sanctionKind ?? (apiCode ? codeKinds[apiCode] : undefined)
  if (!kind || !(sanctionKinds as readonly string[]).includes(kind)) return null
  const parsed = mySanctionSchema.safeParse({
    id: Number(params?.sanctionId) || 1,
    kind,
    reasonCode: params?.reasonCode || 'other',
    reason: params?.reason || null,
    endsAt: params?.endsAt || null,
    status: 'active',
    appealable: params?.appealable === 'true',
    appeal: params?.appealStatus ? { id: 1, status: params.appealStatus } : null,
  })
  return parsed.success ? parsed.data : null
}

/**
 * Verständliche Meldung für `sanctioned` / `chat_muted` / `banned`:
 * „Du bist im Chat stummgeschaltet – endet in 5 Std. (…). Grund: Spam.“
 * `null`, wenn der Fehler keine Strafe ist.
 */
export function sanctionErrorText(apiCode: string | undefined, params: Record<string, string> | undefined): string | null {
  if (apiCode !== 'sanctioned' && apiCode !== 'chat_muted' && apiCode !== 'banned') return null
  const s = sanctionFromParams(apiCode, params)
  if (!s) return null
  const head = t(`sanctions.blocked.${s.kind}`)
  const end = endText(s)
  const reason = params?.reasonCode ? t('sanctions.blocked.reason', { reason: sanctionReasonText(s) }) : ''
  return [head, end, reason].filter(Boolean).join(' · ')
}
