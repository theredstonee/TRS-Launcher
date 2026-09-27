// Typen + kleine Helfer für Team-Seite, Stellen und Bewerbungen (API.md §24.3).
import type { Lang } from './messages'
import type { AdminSanction } from './moderation'

export type Localized = Partial<Record<Lang, string>>
export type FieldType = 'short' | 'long' | 'single' | 'multi' | 'yesno' | 'number'
export type ApplicationStatus = 'new' | 'review' | 'interview' | 'accepted' | 'rejected' | 'withdrawn'
export const AGE_GROUPS = ['under14', '14-15', '16-17', '18+'] as const
export type AgeGroup = (typeof AGE_GROUPS)[number]

export interface FormField {
  id: string
  type: FieldType
  required: boolean
  label: Localized
  help?: Localized
  min?: number
  max?: number
  options?: { id: string, label: Localized }[]
}

export interface JobLangTexts {
  title: string
  summary: string
  description: string
  tasks: string[]
  requirements: string[]
}

export type JobTexts = Partial<Record<Lang, JobLangTexts>>

export interface TeamRole {
  id: string
  name: string | null
  color: string
  builtin: boolean
}

export interface JobView {
  id: string
  status: 'draft' | 'open' | 'closed'
  texts: JobTexts
  form: FormField[]
  cooldownDays: number
  role: TeamRole | null
  createdAt: string
  updatedAt: string
}

export interface AdminJob extends JobView {
  applications: { open: number, total: number }
}

/** Positions-Titel je Sprache (fehlende Sprachen → Englisch → erste vorhandene). */
export type TeamTitles = Partial<Record<'en' | 'de' | 'es', string>>

export interface TeamLink {
  label: string
  url: string
}

/** Mitglied auf der öffentlichen Team-Seite (§26.1). */
export interface PublicTeamMember {
  uuid: string
  name: string
  /** `null` = unbekannt (Kopf mit Anfangsbuchstabe, 3D mit Standard-Figur); `url: null` = Standard-Skin. */
  skin: { url: string | null, model: 'classic' | 'slim' } | null
  cape: { id: string, url: string, frames: number, frameTimeMs: number | null } | null
  titles: TeamTitles
  discord: string | null
  links: TeamLink[]
}

export interface PublicTeam {
  groups: (TeamRole & { members: PublicTeamMember[] })[]
}

/** Team-Seite im Admin (§26.2). */
export interface TeamPageMember {
  uuid: string
  name: string
  roleId: string | null
  titles: TeamTitles
  discord: string | null
  links: TeamLink[]
  mainRole: string | null
  banned: boolean
}

export interface TeamPageAdmin {
  groups: { role: TeamRole & { rank: number, public: boolean }, members: TeamPageMember[] }[]
  ungrouped: TeamPageMember[]
  editable: boolean
}

export interface Eligibility {
  canApply: boolean
  reason: null | 'closed' | 'open_application' | 'cooldown' | 'member' | 'too_many_open'
  retryAt: string | null
  applicationId: string | null
}

export interface MyApplication {
  id: string
  job: { id: string, title: Localized, open: boolean }
  status: ApplicationStatus
  response: string | null
  createdAt: string
  updatedAt: string
  decidedAt: string | null
  canWithdraw: boolean
}

export type AnswerValue = string | number | boolean | string[]

export interface AdminApplicationItem {
  id: string
  job: { id: string, title: Localized }
  applicant: { uuid: string, name: string }
  ageGroup: AgeGroup
  status: ApplicationStatus
  votes: { up: number, down: number, mine: -1 | 1 | null }
  notes: number
  createdAt: string
  updatedAt: string
}

export interface AdminApplicationDetail extends AdminApplicationItem {
  discord: string
  lang: Lang
  form: FormField[]
  answers: Record<string, AnswerValue>
  response: string | null
  decidedAt: string | null
  decidedBy: { uuid: string, name: string | null } | null
  roleGranted: string | null
  jobRole: (TeamRole & { rank: number }) | null
  voteList: { uuid: string, name: string | null, vote: -1 | 1, comment: string | null, at: string }[]
  noteList: { id: number, at: string, actor: { uuid: string, name: string | null }, text: string }[]
  history: { at: string, actor: { uuid: string, name: string | null }, action: string, detail: string | null }[]
  player: { rank: number, sanctions: AdminSanction[], active: number } | null
  can: { review: boolean, decide: boolean, grantRole: boolean, vote: boolean }
}

/** Text in der gewünschten Sprache, sonst Englisch → Deutsch → Spanisch. */
export function inLang<T>(obj: Partial<Record<Lang, T>> | null | undefined, lang: Lang): T | undefined {
  if (!obj) return undefined
  return obj[lang] ?? obj.en ?? obj.de ?? obj.es
}

/** Farbe des Status-Abzeichens. */
export function applicationTone(s: ApplicationStatus): string {
  switch (s) {
    case 'new': return 'tone-warn'
    case 'review':
    case 'interview': return 'tone-info'
    case 'accepted': return 'tone-ok'
    case 'rejected': return 'tone-danger'
    default: return 'tone-muted'
  }
}

/** Discord-Name wie der Server prüft (neue Namen oder alte Form Name#1234). */
export function discordOk(raw: string): boolean {
  const s = raw.trim().replace(/^@/, '')
  const lower = s.toLowerCase()
  if (/^[a-z0-9_.]{2,32}$/.test(lower) && !lower.includes('..')) return true
  return /^[^@#:`\s][^@#:`]{0,30}[^@#:`\s]#\d{4}$/u.test(s)
}
