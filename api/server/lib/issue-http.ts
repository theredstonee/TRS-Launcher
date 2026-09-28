import { getCookie, getHeader, type H3Event } from 'h3'
import { z } from 'zod'
import { ISSUE_AREAS, ISSUE_LIMITS, ISSUE_PRIORITIES, ISSUE_SORTS, ISSUE_STATUSES, ISSUE_TYPES, ISSUE_VERSION, UPLOAD_ID } from '../../shared/issues'
import { useCtx } from './context'
import { clientIp, limit, optionalUser, paramWith, requireWebOrUser } from './http'
import type { Viewer } from './issues'
import { RULES } from './ratelimit'
import { teamOf } from './team'
import { WEB_SESSION_COOKIE, webSession } from './weblogin'

/**
 * HTTP-Helfer für den Issue-Tracker (§28): wer schaut (optional angemeldet – Bearer oder Website-Sitzung), wer
 * schreibt (Pflicht), Grenzen für öffentliche Abrufe und die zod-Schemas der Routen.
 */

/** Optional angemeldet: gültiger Bearer-Token oder Website-Sitzung, sonst `null` (lesen bleibt öffentlich). */
export function issueViewer(event: H3Event): Viewer | null {
  const ctx = useCtx()
  const user = optionalUser(event)
  if (user) return { uuid: user.uuid, name: user.user.name, staff: teamOf(ctx, user.uuid) }
  if (getHeader(event, 'authorization') !== undefined) return null
  const cookie = getCookie(event, WEB_SESSION_COOKIE)
  if (!cookie) return null
  try {
    const s = webSession(ctx, cookie, undefined, false)
    return { uuid: s.uuid, name: s.name, staff: teamOf(ctx, s.uuid) }
  } catch {
    return null
  }
}

/** Öffentliche Abrufe: mit Konto zählt das Lese-Limit des Kontos, ohne das IP-Limit. */
export function publicIssueRead(event: H3Event): Viewer | null {
  const viewer = issueViewer(event)
  if (viewer) limit(`read:${viewer.uuid}`, RULES.readUser)
  else limit(`issuePublic:${clientIp(event)}`, RULES.issuePublicIp)
  event.context.uuid = viewer?.uuid
  return viewer
}

/** Schreiben: Website-Sitzung (mit CSRF) oder Bearer-Token + Grund-Limit; `rule` = zusätzliches Limit der Aktion. */
export function requireIssueWriter(event: H3Event, rule?: keyof typeof RULES): Viewer {
  const auth = requireWebOrUser(event, 'write')
  if (rule) limit(`${rule}:${auth.uuid}`, RULES[rule])
  return { uuid: auth.uuid, name: auth.name, staff: teamOf(useCtx(), auth.uuid) }
}

export function issueNumberParam(event: H3Event): number {
  return paramWith(event, 'number', z.string().regex(/^#?\d{1,9}$/).transform((s) => Number(s.replace('#', ''))))
}

export function commentIdParam(event: H3Event): number {
  return paramWith(event, 'id', z.string().regex(/^\d{1,13}$/).transform(Number))
}

// ---------------------------------------------------------------- Schemas

const text = (max: number) => z.string().max(max * 4)
const list = <T extends readonly [string, ...string[]]>(values: T) =>
  z
    .string()
    .max(200)
    .transform((s) => s.split(',').map((x) => x.trim()).filter(Boolean))
    .pipe(z.array(z.enum(values)).max(values.length))

export const issueListQuery = z.strictObject({
  sort: z.enum(ISSUE_SORTS).default('top'),
  type: z.enum(ISSUE_TYPES).optional(),
  area: z.enum(ISSUE_AREAS).optional(),
  status: list(ISSUE_STATUSES).optional(),
  closed: z.enum(['0', '1', 'true', 'false']).optional().transform((v) => v === '1' || v === 'true'),
  q: z.string().max(ISSUE_LIMITS.queryMax).optional(),
  /** Suchsyntax (shared/issue-query.ts). */
  filter: z.string().max(ISSUE_LIMITS.filterMax).optional(),
  page: z.coerce.number().int().min(1).max(100_000).default(1),
  per: z.coerce.number().int().min(1).max(ISSUE_LIMITS.perPageMax).default(ISSUE_LIMITS.perPage),
  lang: z.string().max(8).optional(),
})

export const roadmapQuery = z.strictObject({
  filter: z.string().max(ISSUE_LIMITS.filterMax).optional(),
  /** Nur eine Spalte weiterblättern („Mehr laden“). */
  column: z.enum(['open', 'planned', 'in_progress', 'in_review', 'done', 'rejected']).optional(),
  offset: z.coerce.number().int().min(0).max(100_000).default(0),
  per: z.coerce.number().int().min(1).max(ISSUE_LIMITS.roadmapPerMax).default(ISSUE_LIMITS.roadmapPer),
  lang: z.string().max(8).optional(),
})

export const adminIssueListQuery = issueListQuery.extend({
  view: z.enum(['all', 'unassigned', 'mine', 'deleted']).default('all'),
  priority: z.enum(ISSUE_PRIORITIES).optional(),
})

const metaField = z.string().max(ISSUE_LIMITS.metaFieldMax)

export const createIssueBody = z.strictObject({
  type: z.enum(ISSUE_TYPES),
  area: z.enum(ISSUE_AREAS),
  title: text(ISSUE_LIMITS.titleMax),
  description: text(ISSUE_LIMITS.descriptionMax),
  attachments: z.array(z.string().regex(UPLOAD_ID)).max(ISSUE_LIMITS.uploadsPerIssue).optional(),
  meta: z
    .strictObject({
      modVersion: metaField.optional(),
      mcVersion: metaField.optional(),
      loader: metaField.optional(),
      mods: z.array(z.string().max(ISSUE_LIMITS.modMax)).max(ISSUE_LIMITS.modsMax).optional(),
      log: z.string().max(ISSUE_LIMITS.logMax).optional(),
    })
    .optional(),
})

export const editIssueBody = z
  .strictObject({
    title: text(ISSUE_LIMITS.titleMax).optional(),
    description: text(ISSUE_LIMITS.descriptionMax).optional(),
    type: z.enum(ISSUE_TYPES).optional(),
  })
  .refine((b) => Object.keys(b).length > 0, 'Nothing to change')

export const voteBody = z.strictObject({ vote: z.union([z.literal(1), z.literal(-1), z.literal(0)]) })

export const commentBody = z.strictObject({
  body: text(ISSUE_LIMITS.commentMax),
  attachments: z.array(z.string().regex(UPLOAD_ID)).max(ISSUE_LIMITS.uploadsPerComment).optional(),
})

export const editCommentBody = z.strictObject({ body: text(ISSUE_LIMITS.commentMax) })

export const adminIssuePatchBody = z
  .strictObject({
    status: z.enum(ISSUE_STATUSES).optional(),
    priority: z.enum(ISSUE_PRIORITIES).nullable().optional(),
    assignee: z.string().regex(/^[0-9a-f]{32}$/).nullable().optional(),
    tags: z.array(z.string().max(24)).max(ISSUE_LIMITS.tagsMax).optional(),
    fixedIn: z.string().regex(ISSUE_VERSION).nullable().optional(),
    type: z.enum(ISSUE_TYPES).optional(),
    area: z.enum(ISSUE_AREAS).optional(),
    title: text(ISSUE_LIMITS.titleMax).optional(),
    locked: z.boolean().optional(),
  })
  .refine((b) => Object.keys(b).length > 0, 'Nothing to change')

export const mergeBody = z.strictObject({ into: z.int().min(1).max(1_000_000_000) })
export const noteBody = z.strictObject({ text: text(ISSUE_LIMITS.noteMax) })
export const deleteIssueBody = z.strictObject({ reason: z.string().max(300).optional() }).optional()
