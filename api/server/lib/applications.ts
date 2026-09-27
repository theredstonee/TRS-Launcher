import { randomBytes } from 'node:crypto'
import { z } from 'zod'
import type { AppContext } from './context'
import { all, one, placeholders, run, tx } from './db'
import { ApiError, badRequest, conflict, forbidden, notFound } from './errors'
import { adminSanctionViews, sanctionsOf, type AdminSanctionView } from './sanctions'
import { sanitizeText, textLength } from './safety'
import { addMemberRole, assertCan, can, getRole, rankOf, type Staff } from './team'

/**
 * Team-Bewerbungen (API.md §24.3): öffentliche Stellen mit Formular-Baukasten, Bewerben nur angemeldet (Website mit
 * Microsoft oder Launcher-Token), eine offene Bewerbung je Stelle, Wartezeit nach Absage, Prüfen im Team mit Notizen,
 * Stimmen und Verlauf, Rückmeldung an Bewerber (Website + Ereignis `application_updated`), optional Rolle bei Annahme.
 */

export const LANGS = ['en', 'de', 'es'] as const
export type Lang = (typeof LANGS)[number]
export const AGE_GROUPS = ['under14', '14-15', '16-17', '18+'] as const
export type AgeGroup = (typeof AGE_GROUPS)[number]
export const FIELD_TYPES = ['short', 'long', 'single', 'multi', 'yesno', 'number'] as const
export type FieldType = (typeof FIELD_TYPES)[number]
export const APPLICATION_STATUSES = ['new', 'review', 'interview', 'accepted', 'rejected', 'withdrawn'] as const
export type ApplicationStatus = (typeof APPLICATION_STATUSES)[number]
export const OPEN_STATUSES: readonly ApplicationStatus[] = ['new', 'review', 'interview']
const FINAL_STATUSES: readonly ApplicationStatus[] = ['accepted', 'rejected', 'withdrawn']

const DAY = 86_400_000
/** Abgelehnte/zurückgezogene Bewerbungen werden so lange nach der Entscheidung gelöscht. */
export const APPLICATION_RETENTION_MS = 182 * DAY
/** Nach dem Zurückziehen kann man sich frühestens nach 24 h erneut auf dieselbe Stelle bewerben. */
export const WITHDRAW_COOLDOWN_MS = DAY
/** Offene Bewerbungen je Konto insgesamt (Spam-Schutz). */
export const MAX_OPEN_PER_USER = 3
export const MAX_JOBS = 50
export const MAX_FIELDS = 30

// ---------------------------------------------------------------- Schemas (Stelle + Formular)

const clean = (max: number, multiline = false) =>
  z.string().max(max * 4).transform((s) => (multiline ? sanitizeText(s) : sanitizeText(s).replace(/\s*\n\s*/g, ' '))).refine((s) => textLength(s) <= max, `at most ${max} characters`)

/** Text je Sprache; mindestens eine Sprache. */
const localized = (max: number, multiline = false) =>
  z.strictObject({ en: clean(max, multiline).optional(), de: clean(max, multiline).optional(), es: clean(max, multiline).optional() })

export type Localized = { en?: string, de?: string, es?: string }

const jobLangTexts = z.strictObject({
  title: clean(80),
  summary: clean(240).default(''),
  description: clean(4000, true).default(''),
  tasks: z.array(clean(200)).max(20).default([]),
  requirements: z.array(clean(200)).max(20).default([]),
})
export type JobLangTexts = z.output<typeof jobLangTexts>

export const jobTextsSchema = z
  .strictObject({ en: jobLangTexts.optional(), de: jobLangTexts.optional(), es: jobLangTexts.optional() })
  .refine((t) => LANGS.some((l) => t[l]?.title), 'at least one language needs a title')
export type JobTexts = z.output<typeof jobTextsSchema>

const FIELD_ID = /^[a-z][a-z0-9_]{0,31}$/
const RESERVED_IDS = new Set(['discord', 'agegroup', 'age_group', 'website', 'name', 'uuid'])

export const formFieldSchema = z
  .strictObject({
    id: z.string().regex(FIELD_ID, 'lowercase letters, digits and _'),
    type: z.enum(FIELD_TYPES),
    required: z.boolean().default(false),
    label: localized(120),
    help: localized(300).optional(),
    /** Text: Zeichen; Zahl: Wert; Mehrfachauswahl: Anzahl. */
    min: z.number().int().min(-1_000_000_000).max(1_000_000_000).optional(),
    max: z.number().int().min(-1_000_000_000).max(1_000_000_000).optional(),
    options: z.array(z.strictObject({ id: z.string().regex(FIELD_ID), label: localized(120) })).max(20).optional(),
  })
  .superRefine((f, c) => {
    if (!LANGS.some((l) => f.label[l])) c.addIssue({ code: 'custom', message: 'label needs at least one language', path: ['label'] })
    if (RESERVED_IDS.has(f.id)) c.addIssue({ code: 'custom', message: 'reserved id', path: ['id'] })
    const choice = f.type === 'single' || f.type === 'multi'
    if (choice) {
      const opts = f.options ?? []
      if (opts.length < 2) c.addIssue({ code: 'custom', message: 'choices need 2–20 options', path: ['options'] })
      if (new Set(opts.map((o) => o.id)).size !== opts.length) c.addIssue({ code: 'custom', message: 'duplicate option id', path: ['options'] })
      if (opts.some((o) => !LANGS.some((l) => o.label[l]))) c.addIssue({ code: 'custom', message: 'option label missing', path: ['options'] })
    } else if (f.options?.length) {
      c.addIssue({ code: 'custom', message: 'only choice fields have options', path: ['options'] })
    }
    if (f.type === 'short' || f.type === 'long') {
      const cap = f.type === 'short' ? 200 : 4000
      if ((f.max ?? 0) > cap || (f.min ?? 0) < 0 || (f.min !== undefined && f.max !== undefined && f.min > f.max)) {
        c.addIssue({ code: 'custom', message: `character limits must be within 0–${cap}`, path: ['max'] })
      }
    }
    if (f.type === 'multi' && (f.min ?? 0) < 0) c.addIssue({ code: 'custom', message: 'invalid selection limits', path: ['min'] })
    if (f.min !== undefined && f.max !== undefined && f.min > f.max) c.addIssue({ code: 'custom', message: 'min > max', path: ['min'] })
  })
export type FormField = z.output<typeof formFieldSchema>

export const formSchema = z
  .array(formFieldSchema)
  .max(MAX_FIELDS)
  .refine((fs) => new Set(fs.map((f) => f.id)).size === fs.length, 'field ids must be unique')

export const JOB_ID = /^[a-z0-9](?:[a-z0-9-]{1,46}[a-z0-9])$/

export const jobInputSchema = z.strictObject({
  id: z.string().regex(JOB_ID, 'lowercase letters, digits and dashes (3–48)').optional(),
  status: z.enum(['draft', 'open', 'closed']).default('draft'),
  roleId: z.string().min(1).max(40).nullable().default(null),
  sort: z.number().int().min(-1000).max(1000).default(0),
  cooldownDays: z.number().int().min(0).max(365).default(30),
  texts: jobTextsSchema,
  form: formSchema.default([]),
})
export type JobInput = z.input<typeof jobInputSchema>

// ---------------------------------------------------------------- Stellen

interface JobRow {
  id: string
  status: 'draft' | 'open' | 'closed'
  role_id: string | null
  sort: number
  cooldown_days: number
  texts: string
  form: string
  created_at: number
  created_by: string
  updated_at: number
}

export interface JobView {
  id: string
  status: 'draft' | 'open' | 'closed'
  texts: JobTexts
  form: FormField[]
  cooldownDays: number
  role: { id: string, name: string | null, color: string, builtin: boolean } | null
  createdAt: string
  updatedAt: string
}

const iso = (t: number) => new Date(t).toISOString()
const isoOrNull = (t: number | null) => (t === null ? null : iso(t))

function parseJson<T>(raw: string, fallback: T): T {
  try {
    return JSON.parse(raw) as T
  } catch {
    return fallback
  }
}

function jobView(ctx: AppContext, j: JobRow): JobView {
  const r = j.role_id ? getRole(ctx, j.role_id) : undefined
  return {
    id: j.id,
    status: j.status,
    texts: parseJson<JobTexts>(j.texts, {}),
    form: parseJson<FormField[]>(j.form, []),
    cooldownDays: j.cooldown_days,
    role: r ? { id: r.id, name: r.name, color: r.color, builtin: r.builtin === 1 } : null,
    createdAt: iso(j.created_at),
    updatedAt: iso(j.updated_at),
  }
}

function jobRow(ctx: AppContext, id: string): JobRow | undefined {
  return one<JobRow>(ctx.db, 'SELECT * FROM team_jobs WHERE id = ?', id)
}

/** Öffentlich: offene (und auf Wunsch geschlossene) Stellen, ohne Entwürfe. */
export function publicJobs(ctx: AppContext, includeClosed = false): JobView[] {
  return all<JobRow>(
    ctx.db,
    `SELECT * FROM team_jobs WHERE status ${includeClosed ? "IN ('open', 'closed')" : "= 'open'"} ORDER BY status = 'open' DESC, sort, created_at`,
  ).map((j) => jobView(ctx, j))
}

export function publicJob(ctx: AppContext, id: string): JobView {
  const j = jobRow(ctx, id)
  if (!j || j.status === 'draft') throw notFound('job_not_found', 'Job not found')
  return jobView(ctx, j)
}

export function adminJobs(ctx: AppContext): (JobView & { applications: { open: number, total: number } })[] {
  const counts = new Map(all<{ job_id: string, open: number, total: number }>(
    ctx.db,
    `SELECT job_id, COALESCE(SUM(status IN ('new', 'review', 'interview')), 0) AS open, COUNT(*) AS total FROM team_applications GROUP BY job_id`,
  ).map((c) => [c.job_id, c]))
  return all<JobRow>(ctx.db, 'SELECT * FROM team_jobs ORDER BY sort, created_at').map((j) => ({
    ...jobView(ctx, j),
    applications: { open: counts.get(j.id)?.open ?? 0, total: counts.get(j.id)?.total ?? 0 },
  }))
}

function slugFrom(texts: JobTexts): string {
  const title = texts.en?.title ?? texts.de?.title ?? texts.es?.title ?? 'job'
  const base = title
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/ß/g, 'ss')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40)
  return base.length >= 3 ? base : `job-${randomBytes(3).toString('hex')}`
}

function checkRole(ctx: AppContext, roleId: string | null): void {
  if (roleId === null) return
  const r = getRole(ctx, roleId)
  if (!r) throw notFound('role_not_found', 'Role not found')
  if (r.id === 'owner') throw badRequest('invalid_role', 'The owner role cannot be linked to a job')
}

export function createJob(ctx: AppContext, actor: Staff, raw: unknown): JobView {
  assertCan(actor, 'applications.manage')
  const input = parse(jobInputSchema, raw)
  checkRole(ctx, input.roleId)
  if (one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM team_jobs')!.n >= MAX_JOBS) throw conflict('job_limit', `At most ${MAX_JOBS} jobs`)
  let id = input.id ?? slugFrom(input.texts)
  if (jobRow(ctx, id)) {
    if (input.id) throw conflict('job_exists', 'A job with this id exists')
    id = `${id.slice(0, 40)}-${randomBytes(2).toString('hex')}`
  }
  const t = ctx.now()
  run(
    ctx.db,
    `INSERT INTO team_jobs (id, status, role_id, sort, cooldown_days, texts, form, created_at, created_by, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    id, input.status, input.roleId, input.sort, input.cooldownDays, JSON.stringify(input.texts), JSON.stringify(input.form), t, actor.uuid, t,
  )
  logAudit(ctx, actor.uuid, 'job.create', null, id)
  return jobView(ctx, jobRow(ctx, id)!)
}

export function updateJob(ctx: AppContext, actor: Staff, id: string, raw: unknown): JobView {
  assertCan(actor, 'applications.manage')
  const j = jobRow(ctx, id)
  if (!j) throw notFound('job_not_found', 'Job not found')
  const input = parse(jobInputSchema.omit({ id: true }), raw)
  checkRole(ctx, input.roleId)
  run(
    ctx.db,
    'UPDATE team_jobs SET status = ?, role_id = ?, sort = ?, cooldown_days = ?, texts = ?, form = ?, updated_at = ? WHERE id = ?',
    input.status, input.roleId, input.sort, input.cooldownDays, JSON.stringify(input.texts), JSON.stringify(input.form), ctx.now(), id,
  )
  logAudit(ctx, actor.uuid, 'job.update', null, `${id}: ${input.status}`)
  return jobView(ctx, jobRow(ctx, id)!)
}

/**
 * Stelle endgültig löschen – nur Owner. Hat sie Bewerbungen, müssen diese ausdrücklich mitgelöscht werden
 * (`withApplications`), sonst 409 `job_has_applications` mit der Anzahl. Notizen, Stimmen und Verlauf der
 * Bewerbungen gehen per CASCADE mit; die Wartezeit nach einer Absage entfällt damit.
 */
export function deleteJob(ctx: AppContext, actor: Staff, id: string, withApplications = false): { applications: number } {
  if (!actor.owner) throw forbidden('owner_only', 'Only owners can delete positions')
  if (!jobRow(ctx, id)) throw notFound('job_not_found', 'Job not found')
  const n = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM team_applications WHERE job_id = ?', id)!.n
  if (n > 0 && !withApplications) {
    throw new ApiError(409, 'job_has_applications', `This job has ${n} applications – confirm deleting them too`, { applications: n })
  }
  tx(ctx.db, () => {
    run(ctx.db, 'DELETE FROM team_applications WHERE job_id = ?', id)
    run(ctx.db, 'DELETE FROM team_jobs WHERE id = ?', id)
    logAudit(ctx, actor.uuid, 'job.delete', null, n ? `${id} (+${n} applications)` : id)
  })
  return { applications: n }
}

function logAudit(ctx: AppContext, actor: string, action: string, target: string | null, detail: string, ref?: string): void {
  run(ctx.db, 'INSERT INTO admin_log (at, actor, action, target, detail, ref) VALUES (?, ?, ?, ?, ?, ?)', ctx.now(), actor, action, target, detail, ref ?? null)
}

function parse<S extends z.ZodType>(schema: S, value: unknown): z.output<S> {
  const r = schema.safeParse(value)
  if (!r.success) {
    throw badRequest('invalid_request', 'Request validation failed', {
      fields: r.error.issues.slice(0, 20).map((i) => ({ path: i.path.join('.') || '(body)', message: i.message })),
    })
  }
  return r.data
}

// ---------------------------------------------------------------- Antworten prüfen

/** Discord-Name: neue Namen (2–32, a–z 0–9 _ ., keine zwei Punkte) oder alte Form `Name#1234`. */
export function normalizeDiscord(raw: string): string | null {
  const s = raw.normalize('NFC').trim().replace(/^@/, '')
  const lower = s.toLowerCase()
  if (/^[a-z0-9_.]{2,32}$/.test(lower) && !lower.includes('..')) return lower
  if (/^[^@#:`\s][^@#:`]{0,30}[^@#:`\s]#\d{4}$/u.test(s) && textLength(s) <= 37) return s
  return null
}

export type AnswerValue = string | number | boolean | string[]

export interface AnswerError {
  id: string
  error: 'required' | 'too_short' | 'too_long' | 'invalid' | 'too_few' | 'too_many' | 'too_small' | 'too_large'
}

/** Antworten gegen das Formular prüfen und säubern. Unbekannte Felder → Fehler (nichts wird still verworfen). */
export function validateAnswers(form: FormField[], raw: unknown): { answers: Record<string, AnswerValue>, errors: AnswerError[] } {
  const answers: Record<string, AnswerValue> = {}
  const errors: AnswerError[] = []
  const input = raw && typeof raw === 'object' && !Array.isArray(raw) ? (raw as Record<string, unknown>) : {}
  const known = new Set(form.map((f) => f.id))
  for (const k of Object.keys(input)) if (!known.has(k)) errors.push({ id: k.slice(0, 32), error: 'invalid' })
  for (const f of form) {
    const v = input[f.id]
    const empty = v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0)
    if (empty) {
      if (f.required) errors.push({ id: f.id, error: 'required' })
      continue
    }
    switch (f.type) {
      case 'short':
      case 'long': {
        if (typeof v !== 'string' || v.length > 20_000) {
          errors.push({ id: f.id, error: 'invalid' })
          break
        }
        const text = f.type === 'short' ? sanitizeText(v).replace(/\s*\n\s*/g, ' ') : sanitizeText(v)
        const len = textLength(text)
        const max = f.max ?? (f.type === 'short' ? 100 : 1000)
        if (len === 0) {
          if (f.required) errors.push({ id: f.id, error: 'required' })
        } else if (f.min !== undefined && len < f.min) errors.push({ id: f.id, error: 'too_short' })
        else if (len > max) errors.push({ id: f.id, error: 'too_long' })
        else answers[f.id] = text
        break
      }
      case 'number': {
        const n = typeof v === 'number' ? v : typeof v === 'string' && /^-?\d{1,10}$/.test(v.trim()) ? Number(v.trim()) : NaN
        if (!Number.isInteger(n)) errors.push({ id: f.id, error: 'invalid' })
        else if (f.min !== undefined && n < f.min) errors.push({ id: f.id, error: 'too_small' })
        else if (f.max !== undefined && n > f.max) errors.push({ id: f.id, error: 'too_large' })
        else answers[f.id] = n
        break
      }
      case 'yesno': {
        if (typeof v !== 'boolean') errors.push({ id: f.id, error: 'invalid' })
        else answers[f.id] = v
        break
      }
      case 'single': {
        if (typeof v !== 'string' || !(f.options ?? []).some((o) => o.id === v)) errors.push({ id: f.id, error: 'invalid' })
        else answers[f.id] = v
        break
      }
      case 'multi': {
        const ids = new Set((f.options ?? []).map((o) => o.id))
        if (!Array.isArray(v) || v.some((x) => typeof x !== 'string' || !ids.has(x))) {
          errors.push({ id: f.id, error: 'invalid' })
          break
        }
        const picked = [...new Set(v as string[])]
        if (f.min !== undefined && picked.length < f.min) errors.push({ id: f.id, error: 'too_few' })
        else if (f.max !== undefined && picked.length > f.max) errors.push({ id: f.id, error: 'too_many' })
        else answers[f.id] = picked
        break
      }
    }
  }
  return { answers, errors }
}

// ---------------------------------------------------------------- Bewerben

export const applySchema = z.strictObject({
  discord: z.string().min(1).max(64),
  ageGroup: z.enum(AGE_GROUPS),
  answers: z.record(z.string().max(32), z.unknown()).default({}),
  lang: z.enum(LANGS).default('en'),
  /** Honigtopf: echte Nutzer lassen das Feld leer (unsichtbar im Formular). */
  website: z.string().max(200).optional(),
})
export type ApplyInput = z.input<typeof applySchema>

interface ApplicationRow {
  id: string
  job_id: string
  uuid: string
  name: string
  discord: string
  age_group: AgeGroup
  answers: string
  form: string
  lang: Lang
  status: ApplicationStatus
  response: string | null
  role_granted: string | null
  created_at: number
  updated_at: number
  decided_at: number | null
  decided_by: string | null
  retain_until: number | null
}

export interface Eligibility {
  canApply: boolean
  reason: null | 'closed' | 'open_application' | 'cooldown' | 'member' | 'too_many_open'
  retryAt: string | null
  applicationId: string | null
}

export function eligibility(ctx: AppContext, uuid: string, jobId: string): Eligibility {
  const j = jobRow(ctx, jobId)
  if (!j || j.status === 'draft') throw notFound('job_not_found', 'Job not found')
  const no = (reason: Eligibility['reason'], retryAt: number | null = null, applicationId: string | null = null): Eligibility =>
    ({ canApply: false, reason, retryAt: isoOrNull(retryAt), applicationId })
  const open = one<{ id: string }>(
    ctx.db, "SELECT id FROM team_applications WHERE job_id = ? AND uuid = ? AND status IN ('new', 'review', 'interview')", jobId, uuid,
  )
  if (open) return no('open_application', null, open.id)
  if (j.status !== 'open') return no('closed')
  if (j.role_id && one(ctx.db, 'SELECT 1 AS x FROM team_members WHERE uuid = ? AND role_id = ?', uuid, j.role_id)) return no('member')
  const last = one<{ status: ApplicationStatus, decided_at: number | null, updated_at: number }>(
    ctx.db,
    "SELECT status, decided_at, updated_at FROM team_applications WHERE job_id = ? AND uuid = ? AND status IN ('rejected', 'withdrawn') ORDER BY updated_at DESC LIMIT 1",
    jobId, uuid,
  )
  if (last) {
    const at = last.decided_at ?? last.updated_at
    const until = at + (last.status === 'rejected' ? j.cooldown_days * DAY : WITHDRAW_COOLDOWN_MS)
    if (until > ctx.now()) return no('cooldown', until)
  }
  const openTotal = one<{ n: number }>(ctx.db, "SELECT COUNT(*) AS n FROM team_applications WHERE uuid = ? AND status IN ('new', 'review', 'interview')", uuid)!.n
  if (openTotal >= MAX_OPEN_PER_USER) return no('too_many_open')
  return { canApply: true, reason: null, retryAt: null, applicationId: null }
}

export function submitApplication(ctx: AppContext, applicant: { uuid: string, name: string }, jobId: string, raw: unknown): MyApplicationView {
  const input = parse(applySchema, raw)
  if (input.website) throw badRequest('invalid_request', 'Request validation failed')
  const el = eligibility(ctx, applicant.uuid, jobId)
  if (!el.canApply) {
    const codes: Record<NonNullable<Eligibility['reason']>, [number, string, string]> = {
      closed: [409, 'job_closed', 'This job is not open for applications'],
      open_application: [409, 'application_open', 'You already have an open application for this job'],
      cooldown: [409, 'cooldown', 'You can apply for this job again later'],
      member: [409, 'already_member', 'You already have this role'],
      too_many_open: [409, 'too_many_open', `At most ${MAX_OPEN_PER_USER} open applications at a time`],
    }
    const [status, code, message] = codes[el.reason!]
    throw new ApiError(status, code, message, { retryAt: el.retryAt, applicationId: el.applicationId })
  }
  const discord = normalizeDiscord(input.discord)
  const form = parseJson<FormField[]>(jobRow(ctx, jobId)!.form, [])
  const { answers, errors } = validateAnswers(form, input.answers)
  if (!discord) errors.unshift({ id: 'discord', error: 'invalid' })
  if (errors.length > 0) throw badRequest('invalid_answers', 'Some answers are missing or invalid', { fields: errors.slice(0, 40) })
  const id = `a${randomBytes(8).toString('hex')}`
  const t = ctx.now()
  tx(ctx.db, () => {
    run(
      ctx.db,
      `INSERT INTO team_applications (id, job_id, uuid, name, discord, age_group, answers, form, lang, status, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'new', ?, ?)`,
      id, jobId, applicant.uuid, applicant.name, discord!, input.ageGroup, JSON.stringify(answers), JSON.stringify(form), input.lang, t, t,
    )
    history(ctx, id, applicant.uuid, 'submitted', null)
  })
  const view = myApplicationView(ctx, appRow(ctx, id)!)
  ctx.events.publish(applicant.uuid, { type: 'application_updated', application: view }, { meOnly: true })
  return view
}

function history(ctx: AppContext, id: string, actor: string, action: string, detail: string | null): void {
  run(ctx.db, 'INSERT INTO team_application_history (application_id, at, actor, action, detail) VALUES (?, ?, ?, ?, ?)', id, ctx.now(), actor, action, detail)
}

function appRow(ctx: AppContext, id: string): ApplicationRow | undefined {
  return one<ApplicationRow>(ctx.db, 'SELECT * FROM team_applications WHERE id = ?', id)
}

// ---------------------------------------------------------------- Bewerber-Sicht

export interface MyApplicationView {
  id: string
  job: { id: string, title: Localized, open: boolean }
  status: ApplicationStatus
  /** Antwort des Teams an den Bewerber (letzte). */
  response: string | null
  createdAt: string
  updatedAt: string
  decidedAt: string | null
  canWithdraw: boolean
}

function titles(texts: JobTexts): Localized {
  const out: Localized = {}
  for (const l of LANGS) if (texts[l]?.title) out[l] = texts[l]!.title
  return out
}

function myApplicationView(ctx: AppContext, a: ApplicationRow): MyApplicationView {
  const j = jobRow(ctx, a.job_id)
  return {
    id: a.id,
    job: { id: a.job_id, title: j ? titles(parseJson<JobTexts>(j.texts, {})) : {}, open: j?.status === 'open' },
    status: a.status,
    response: a.response,
    createdAt: iso(a.created_at),
    updatedAt: iso(a.updated_at),
    decidedAt: isoOrNull(a.decided_at),
    canWithdraw: OPEN_STATUSES.includes(a.status),
  }
}

export function myApplications(ctx: AppContext, uuid: string): MyApplicationView[] {
  return all<ApplicationRow>(ctx.db, 'SELECT * FROM team_applications WHERE uuid = ? ORDER BY created_at DESC', uuid).map((a) => myApplicationView(ctx, a))
}

export function withdrawApplication(ctx: AppContext, uuid: string, id: string): MyApplicationView {
  const a = appRow(ctx, id)
  if (!a || a.uuid !== uuid) throw notFound('application_not_found', 'Application not found')
  if (!OPEN_STATUSES.includes(a.status)) throw conflict('application_closed', 'This application is already closed')
  const t = ctx.now()
  tx(ctx.db, () => {
    run(ctx.db, "UPDATE team_applications SET status = 'withdrawn', updated_at = ?, decided_at = ? WHERE id = ?", t, t, id)
    history(ctx, id, uuid, 'status', 'withdrawn')
  })
  const view = myApplicationView(ctx, appRow(ctx, id)!)
  ctx.events.publish(uuid, { type: 'application_updated', application: view }, { meOnly: true })
  return view
}

// ---------------------------------------------------------------- Team-Sicht

export interface VoteSummary {
  up: number
  down: number
  /** Eigene Stimme des Betrachters. */
  mine: -1 | 1 | null
}

export interface AdminApplicationListItem {
  id: string
  job: { id: string, title: Localized }
  applicant: { uuid: string, name: string }
  ageGroup: AgeGroup
  status: ApplicationStatus
  votes: VoteSummary
  notes: number
  createdAt: string
  updatedAt: string
}

export interface AdminApplicationQuery {
  status?: ApplicationStatus | 'open' | 'all'
  job?: string
  q?: string
  cursor?: string
  limit: number
}

function voteSummaries(ctx: AppContext, ids: string[], viewer: string): Map<string, VoteSummary> {
  const out = new Map<string, VoteSummary>()
  for (const id of ids) out.set(id, { up: 0, down: 0, mine: null })
  if (ids.length === 0) return out
  for (const v of all<{ application_id: string, uuid: string, vote: -1 | 1 }>(
    ctx.db, `SELECT application_id, uuid, vote FROM team_application_votes WHERE application_id IN (${placeholders(ids.length)})`, ...ids,
  )) {
    const s = out.get(v.application_id)!
    if (v.vote === 1) s.up++
    else s.down++
    if (v.uuid === viewer) s.mine = v.vote
  }
  return out
}

export function adminListApplications(ctx: AppContext, viewer: Staff, q: AdminApplicationQuery): { applications: AdminApplicationListItem[], nextCursor: string | null, counts: Record<ApplicationStatus, number> } {
  const where: string[] = ['a.uuid <> ?']
  const params: (string | number)[] = [viewer.uuid]
  if (q.status === 'open' || q.status === undefined) where.push("a.status IN ('new', 'review', 'interview')")
  else if (q.status !== 'all') {
    where.push('a.status = ?')
    params.push(q.status)
  }
  if (q.job) {
    where.push('a.job_id = ?')
    params.push(q.job)
  }
  if (q.q) {
    where.push('(lower(a.name) >= ? AND lower(a.name) < ?)')
    params.push(q.q.toLowerCase(), `${q.q.toLowerCase()}￿`)
  }
  if (q.cursor) {
    const m = /^(\d{1,15}):([a-z0-9]{1,40})$/.exec(Buffer.from(q.cursor, 'base64url').toString('utf8'))
    if (!m) throw badRequest('invalid_cursor', 'Invalid cursor')
    where.push('(a.created_at < ? OR (a.created_at = ? AND a.id < ?))')
    params.push(Number(m[1]), Number(m[1]), m[2]!)
  }
  const rows = all<ApplicationRow & { notes: number }>(
    ctx.db,
    `SELECT a.*, (SELECT COUNT(*) FROM team_application_notes n WHERE n.application_id = a.id) AS notes
       FROM team_applications a WHERE ${where.join(' AND ')} ORDER BY a.created_at DESC, a.id DESC LIMIT ?`,
    ...params, q.limit + 1,
  )
  const page = rows.slice(0, q.limit)
  const votes = voteSummaries(ctx, page.map((a) => a.id), viewer.uuid)
  const jobTitles = new Map<string, Localized>()
  for (const j of all<JobRow>(ctx.db, 'SELECT * FROM team_jobs')) jobTitles.set(j.id, titles(parseJson<JobTexts>(j.texts, {})))
  const counts = Object.fromEntries(APPLICATION_STATUSES.map((s) => [s, 0])) as Record<ApplicationStatus, number>
  for (const c of all<{ status: ApplicationStatus, n: number }>(ctx.db, 'SELECT status, COUNT(*) AS n FROM team_applications WHERE uuid <> ? GROUP BY status', viewer.uuid)) counts[c.status] = c.n
  const last = page[page.length - 1]
  return {
    applications: page.map((a) => ({
      id: a.id,
      job: { id: a.job_id, title: jobTitles.get(a.job_id) ?? {} },
      applicant: { uuid: a.uuid, name: a.name },
      ageGroup: a.age_group,
      status: a.status,
      votes: votes.get(a.id)!,
      notes: a.notes,
      createdAt: iso(a.created_at),
      updatedAt: iso(a.updated_at),
    })),
    nextCursor: rows.length > q.limit && last ? Buffer.from(`${last.created_at}:${last.id}`).toString('base64url') : null,
    counts,
  }
}

export interface AdminApplicationDetail extends AdminApplicationListItem {
  discord: string
  lang: Lang
  /** Formular zum Zeitpunkt der Bewerbung + Antworten. */
  form: FormField[]
  answers: Record<string, AnswerValue>
  response: string | null
  decidedAt: string | null
  decidedBy: { uuid: string, name: string | null } | null
  roleGranted: string | null
  jobRole: { id: string, name: string | null, color: string, builtin: boolean, rank: number } | null
  voteList: { uuid: string, name: string | null, vote: -1 | 1, comment: string | null, at: string }[]
  noteList: { id: number, at: string, actor: { uuid: string, name: string | null }, text: string }[]
  history: { at: string, actor: { uuid: string, name: string | null }, action: string, detail: string | null }[]
  /** Strafverlauf + Team-Zugehörigkeit, nur mit `players.view` (sonst `null`). */
  player: { rank: number, sanctions: AdminSanctionView[], active: number } | null
  can: { review: boolean, decide: boolean, grantRole: boolean, vote: boolean }
}

function names(ctx: AppContext, uuids: string[]): Map<string, string> {
  const list = [...new Set(uuids.filter((u) => u.length === 32))]
  const out = new Map<string, string>()
  if (list.length === 0) return out
  for (const r of all<{ uuid: string, name: string }>(ctx.db, `SELECT uuid, name FROM users WHERE uuid IN (${placeholders(list.length)})`, ...list)) out.set(r.uuid, r.name)
  return out
}

function ownOr404(ctx: AppContext, viewer: Staff, id: string): ApplicationRow {
  const a = appRow(ctx, id)
  if (!a) throw notFound('application_not_found', 'Application not found')
  // Eigene Bewerbungen prüfen andere (keine Einsicht in Stimmen/Notizen über sich selbst).
  if (a.uuid === viewer.uuid) throw forbidden('own_application', 'Your own application is reviewed by others')
  return a
}

export function adminApplicationDetail(ctx: AppContext, viewer: Staff, id: string): AdminApplicationDetail {
  const a = ownOr404(ctx, viewer, id)
  const j = jobRow(ctx, a.job_id)
  const votes = all<{ uuid: string, vote: -1 | 1, comment: string | null, at: number }>(
    ctx.db, 'SELECT uuid, vote, comment, at FROM team_application_votes WHERE application_id = ? ORDER BY at', id,
  )
  const notes = all<{ id: number, at: number, actor: string, text: string }>(
    ctx.db, 'SELECT id, at, actor, text FROM team_application_notes WHERE application_id = ? ORDER BY at', id,
  )
  const hist = all<{ at: number, actor: string, action: string, detail: string | null }>(
    ctx.db, 'SELECT at, actor, action, detail FROM team_application_history WHERE application_id = ? ORDER BY id', id,
  )
  const nm = names(ctx, [...votes.map((v) => v.uuid), ...notes.map((n) => n.actor), ...hist.map((h) => h.actor), a.decided_by ?? ''])
  const ref = (u: string) => ({ uuid: u, name: nm.get(u) ?? null })
  const role = j?.role_id ? getRole(ctx, j.role_id) : undefined
  const summary = voteSummaries(ctx, [id], viewer.uuid).get(id)!
  const open = OPEN_STATUSES.includes(a.status)
  let player: AdminApplicationDetail['player'] = null
  if (can(viewer, 'players.view')) {
    const sanctions = adminSanctionViews(ctx, sanctionsOf(ctx, a.uuid, 50))
    player = { rank: rankOf(ctx, a.uuid), sanctions, active: sanctions.filter((s) => s.status === 'active').length }
  }
  return {
    id: a.id,
    job: { id: a.job_id, title: j ? titles(parseJson<JobTexts>(j.texts, {})) : {} },
    applicant: { uuid: a.uuid, name: a.name },
    ageGroup: a.age_group,
    status: a.status,
    votes: summary,
    notes: notes.length,
    createdAt: iso(a.created_at),
    updatedAt: iso(a.updated_at),
    discord: a.discord,
    lang: a.lang,
    form: parseJson<FormField[]>(a.form, []),
    answers: parseJson<Record<string, AnswerValue>>(a.answers, {}),
    response: a.response,
    decidedAt: isoOrNull(a.decided_at),
    decidedBy: a.decided_by ? ref(a.decided_by) : null,
    roleGranted: a.role_granted,
    jobRole: role ? { id: role.id, name: role.name, color: role.color, builtin: role.builtin === 1, rank: role.rank } : null,
    voteList: votes.map((v) => ({ ...ref(v.uuid), vote: v.vote, comment: v.comment, at: iso(v.at) })),
    noteList: notes.map((n) => ({ id: n.id, at: iso(n.at), actor: ref(n.actor), text: n.text })),
    history: hist.map((h) => ({ at: iso(h.at), actor: ref(h.actor), action: h.action, detail: h.detail })),
    player,
    can: {
      review: can(viewer, 'applications.review') && a.status !== 'withdrawn',
      decide: can(viewer, 'applications.decide') && a.status !== 'withdrawn',
      grantRole: !!role && can(viewer, 'applications.decide') && (viewer.owner || (role.rank < viewer.rank && rankOf(ctx, a.uuid) < viewer.rank)),
      vote: can(viewer, 'applications.review') && open,
    },
  }
}

export const statusBody = z.strictObject({
  status: z.enum(['new', 'review', 'interview', 'accepted', 'rejected']),
  response: z.string().max(8000).transform((s) => sanitizeText(s)).refine((s) => textLength(s) <= 2000, 'at most 2000 characters').optional(),
  grantRole: z.boolean().optional(),
})

/**
 * Status ändern: `new`/`review`/`interview` braucht `applications.review`, `accepted`/`rejected` sowie das Wiederöffnen
 * einer entschiedenen Bewerbung `applications.decide`. Bei `accepted` + `grantRole` bekommt der Bewerber die Rolle der
 * Stelle (Rang-Regel). Zurückgezogene Bewerbungen sind fest.
 */
export function setApplicationStatus(ctx: AppContext, actor: Staff, id: string, raw: unknown): AdminApplicationDetail {
  const body = parse(statusBody, raw)
  const a = ownOr404(ctx, actor, id)
  if (a.status === 'withdrawn') throw conflict('application_closed', 'The applicant withdrew this application')
  const deciding = body.status === 'accepted' || body.status === 'rejected'
  const reopening = FINAL_STATUSES.includes(a.status) && !deciding
  assertCan(actor, deciding || reopening ? 'applications.decide' : 'applications.review')
  if (body.grantRole && body.status !== 'accepted') throw badRequest('invalid_request', 'grantRole needs status=accepted')
  const j = jobRow(ctx, a.job_id)
  let grant: string | null = null
  if (body.grantRole) {
    if (!j?.role_id) throw conflict('job_without_role', 'This job has no linked role')
    const role = getRole(ctx, j.role_id)!
    if (!actor.owner && role.rank >= actor.rank) throw forbidden('rank_too_low', 'You can only give roles below your own rank')
    grant = role.id
  }
  const changed = a.status !== body.status
  const responseChanged = body.response !== undefined && body.response !== (a.response ?? '')
  if (!changed && !responseChanged && !grant) return adminApplicationDetail(ctx, actor, id)
  const t = ctx.now()
  tx(ctx.db, () => {
    if (changed) {
      run(
        ctx.db,
        'UPDATE team_applications SET status = ?, updated_at = ?, decided_at = ?, decided_by = ? WHERE id = ?',
        body.status, t, deciding ? t : null, deciding ? actor.uuid : null, id,
      )
      history(ctx, id, actor.uuid, 'status', `${a.status} → ${body.status}`)
    }
    if (responseChanged) {
      run(ctx.db, 'UPDATE team_applications SET response = ?, updated_at = ? WHERE id = ?', body.response || null, t, id)
      history(ctx, id, actor.uuid, 'response', null)
    }
    if (grant) {
      addMemberRole(ctx, actor, a.uuid, grant, `app:${id}`)
      run(ctx.db, 'UPDATE team_applications SET role_granted = ? WHERE id = ?', grant, id)
      history(ctx, id, actor.uuid, 'role', grant)
    }
    logAudit(ctx, actor.uuid, `application.${changed ? body.status : 'response'}`, a.uuid, `${a.job_id}${grant ? ` + role ${grant}` : ''}`, `app:${id}`)
  })
  ctx.events.publish(a.uuid, { type: 'application_updated', application: myApplicationView(ctx, appRow(ctx, id)!) }, { meOnly: true })
  return adminApplicationDetail(ctx, actor, id)
}

export const voteBody = z.strictObject({
  vote: z.union([z.literal(1), z.literal(-1)]),
  comment: z.string().max(2000).transform((s) => sanitizeText(s)).refine((s) => textLength(s) <= 500, 'at most 500 characters').optional(),
})

export function voteApplication(ctx: AppContext, actor: Staff, id: string, raw: unknown): AdminApplicationDetail {
  assertCan(actor, 'applications.review')
  const body = parse(voteBody, raw)
  const a = ownOr404(ctx, actor, id)
  if (!OPEN_STATUSES.includes(a.status)) throw conflict('application_closed', 'Votes are only possible while the application is open')
  tx(ctx.db, () => {
    run(
      ctx.db,
      `INSERT INTO team_application_votes (application_id, uuid, vote, comment, at) VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(application_id, uuid) DO UPDATE SET vote = excluded.vote, comment = excluded.comment, at = excluded.at`,
      id, actor.uuid, body.vote, body.comment || null, ctx.now(),
    )
    history(ctx, id, actor.uuid, 'vote', body.vote === 1 ? 'up' : 'down')
  })
  return adminApplicationDetail(ctx, actor, id)
}

export function removeVote(ctx: AppContext, actor: Staff, id: string): AdminApplicationDetail {
  assertCan(actor, 'applications.review')
  const a = ownOr404(ctx, actor, id)
  if (!OPEN_STATUSES.includes(a.status)) throw conflict('application_closed', 'Votes are only possible while the application is open')
  if (run(ctx.db, 'DELETE FROM team_application_votes WHERE application_id = ? AND uuid = ?', id, actor.uuid) > 0) {
    history(ctx, id, actor.uuid, 'vote', 'removed')
  }
  return adminApplicationDetail(ctx, actor, id)
}

export const noteBody = z.strictObject({
  text: z.string().min(1).max(8000).transform((s) => sanitizeText(s)).refine((s) => s.length > 0 && textLength(s) <= 2000, '1–2000 characters'),
})

export function addApplicationNote(ctx: AppContext, actor: Staff, id: string, raw: unknown): AdminApplicationDetail {
  assertCan(actor, 'applications.review')
  const body = parse(noteBody, raw)
  ownOr404(ctx, actor, id)
  run(ctx.db, 'INSERT INTO team_application_notes (application_id, at, actor, text) VALUES (?, ?, ?, ?)', id, ctx.now(), actor.uuid, body.text)
  return adminApplicationDetail(ctx, actor, id)
}

// ---------------------------------------------------------------- Aufbewahrung

/**
 * Löschfristen (§24.3): abgelehnt/zurückgezogen → 6 Monate nach der Entscheidung. Angenommen → solange im Team, danach
 * 6 Monate (Frist beginnt, sobald der Lauf bemerkt, dass keine Rolle mehr da ist). Offene bleiben bis zur Entscheidung.
 */
export function sweepApplications(ctx: AppContext): number {
  const t = ctx.now()
  let n = run(
    ctx.db,
    "DELETE FROM team_applications WHERE status IN ('rejected', 'withdrawn') AND COALESCE(decided_at, updated_at) < ?",
    t - APPLICATION_RETENTION_MS,
  )
  run(
    ctx.db,
    `UPDATE team_applications SET retain_until = ? WHERE status = 'accepted' AND retain_until IS NULL
       AND uuid NOT IN (SELECT uuid FROM team_members)`,
    t + APPLICATION_RETENTION_MS,
  )
  run(ctx.db, "UPDATE team_applications SET retain_until = NULL WHERE status = 'accepted' AND uuid IN (SELECT uuid FROM team_members)")
  n += run(ctx.db, "DELETE FROM team_applications WHERE status = 'accepted' AND retain_until IS NOT NULL AND retain_until < ?", t)
  return n
}

/** Zähler für Seitenleiste/Übersicht. */
export function applicationCounts(ctx: AppContext, viewer: string): { open: number, new: number } {
  const r = one<{ open: number, fresh: number }>(
    ctx.db,
    "SELECT COALESCE(SUM(status IN ('new', 'review', 'interview')), 0) AS open, COALESCE(SUM(status = 'new'), 0) AS fresh FROM team_applications WHERE uuid <> ?",
    viewer,
  )!
  return { open: r.open, new: r.fresh }
}
