// Issue-Tracker der Website (API §28) – nur die Benachrichtigungen im Launcher: Schema des Live-Ereignisses
// `issue_updated` und der Text der Sozial-Benachrichtigung. Einen eigenen Issue-Bereich hat der Launcher nicht;
// „Ansehen“ öffnet das Issue auf der Website. Getestet in tests/issues.test.ts (ohne Nuxt-Auto-Imports).
import { z } from 'zod'
import { t } from './i18n'

const uuid = z.string().regex(/^[0-9a-f]{32}$/)
const user = z.object({ uuid, name: z.string().max(32) })
/** Der Kern baut die Adresse selbst (nur eigene Website) – hier trotzdem nur https. */
const url = z.string().max(300).startsWith('https://')

export const ISSUE_STATUSES = ['open', 'planned', 'in_progress', 'in_review', 'done', 'rejected', 'duplicate'] as const
export type IssueStatus = (typeof ISSUE_STATUSES)[number]

const issueRef = z.object({
  number: z.number().int().positive(),
  title: z.string().max(160),
  kind: z.enum(['bug', 'feature']).nullable(),
  area: z.enum(['launcher', 'client', 'website']).nullable(),
  url,
})

/** Live-Ereignisse (`trs-live`) des Issue-Trackers. */
export const issueEventSchemas = [
  z.object({
    type: z.literal('issue_updated'),
    change: z.enum(['status', 'team_comment', 'fixed', 'merged']),
    issue: issueRef,
    by: user.nullable(),
    status: z.enum(ISSUE_STATUSES),
    fixedIn: z.string().max(40).nullable(),
    mergedInto: issueRef.nullable(),
    excerpt: z.string().max(200).nullable(),
    at: z.string().max(40).nullable(),
  }),
] as const

export type IssueUpdatedEvent = z.infer<(typeof issueEventSchemas)[0]>

/** Titel + Text der Benachrichtigung (Sprache des Launchers). */
export function issueToastText(e: IssueUpdatedEvent): { title: string, body: string } {
  const title = t('issues.toast.title', { number: e.issue.number, title: e.issue.title })
  switch (e.change) {
    case 'fixed':
      return { title, body: e.fixedIn ? t('issues.toast.fixed', { version: e.fixedIn }) : t('issues.toast.status', { status: t(`issues.status.${e.status}`) }) }
    case 'team_comment':
      return {
        title,
        body: e.excerpt
          ? t('issues.toast.teamCommentText', { name: e.by?.name ?? t('issues.toast.team'), text: e.excerpt })
          : t('issues.toast.teamComment', { name: e.by?.name ?? t('issues.toast.team') }),
      }
    case 'merged':
      return {
        title,
        body: e.mergedInto
          ? t('issues.toast.merged', { number: e.mergedInto.number, title: e.mergedInto.title })
          : t('issues.toast.status', { status: t('issues.status.duplicate') }),
      }
    default:
      return { title, body: t('issues.toast.status', { status: t(`issues.status.${e.status}`) }) }
  }
}

/** Adresse, die „Ansehen“ öffnet (nach dem Zusammenführen das neue Issue). */
export function issueToastUrl(e: IssueUpdatedEvent): string {
  return e.change === 'merged' && e.mergedInto ? e.mergedInto.url : e.issue.url
}
