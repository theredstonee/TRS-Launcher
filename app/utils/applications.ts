import { z } from 'zod'
// Relativ importiert, damit Tests die Datei ohne Nuxt laden können.
import { currentLocale, t } from './i18n'

// Eigene Team-Bewerbungen (API §24.3): Status und Antwort des Teams. Stellen,
// Formulare und das Prüfen im Team liegen auf der Website; der Launcher zeigt
// den Stand, meldet Änderungen (`application_updated`) und kann zurückziehen.

export const applicationStatuses = ['new', 'review', 'interview', 'accepted', 'rejected', 'withdrawn'] as const
export type ApplicationStatus = (typeof applicationStatuses)[number]

const title = z.string().max(80).optional().catch(undefined)

export const myApplicationSchema = z.object({
  id: z.string().regex(/^[a-z0-9_-]{2,40}$/),
  job: z.object({
    id: z.string().regex(/^[a-z0-9_-]{2,40}$/),
    title: z.object({ en: title, de: title, es: title }).catch({}).default({}),
    open: z.boolean().catch(false).default(false),
  }),
  status: z.enum(applicationStatuses),
  response: z.string().max(2000).nullable().catch(null).default(null),
  createdAt: z.string().max(40).nullable().catch(null).default(null),
  updatedAt: z.string().max(40).nullable().catch(null).default(null),
  decidedAt: z.string().max(40).nullable().catch(null).default(null),
  canWithdraw: z.boolean().catch(false).default(false),
})
export type MyApplication = z.infer<typeof myApplicationSchema>

/** Liste: kaputte Einträge fallen weg, statt alles scheitern zu lassen. */
export const myApplicationsSchema = z
  .array(z.unknown())
  .max(200)
  .transform((list) => list.flatMap((x) => {
    const r = myApplicationSchema.safeParse(x)
    return r.success ? [r.data] : []
  }))

/** Stellen-Seite und „Meine Bewerbungen“ auf der Website. */
export const APPLICATIONS_URL = 'https://trs-launcher.theredstonee.de/applications'
export const TEAM_PAGE_URL = 'https://trs-launcher.theredstonee.de/team'

export function isOpenApplication(a: Pick<MyApplication, 'status'>): boolean {
  return a.status === 'new' || a.status === 'review' || a.status === 'interview'
}

/** Stellentitel in der Launcher-Sprache, sonst Englisch, sonst die ID. */
export function jobTitle(a: Pick<MyApplication, 'job'>, lang: string = currentLocale.value): string {
  const base = lang.split('-')[0] as 'en' | 'de' | 'es'
  const titles = a.job.title
  return titles[base] ?? titles.en ?? titles.de ?? titles.es ?? a.job.id
}

export function applicationStatusLabel(status: ApplicationStatus): string {
  return t(`applications.status.${status}`)
}

/** Text des Hinweises zu `application_updated`: „Deine Bewerbung als X: Status“ + Antwort. */
export function applicationToast(a: MyApplication): { title: string; body: string } {
  const title = t('applications.toast.title', { job: jobTitle(a) })
  const status = t(`applications.toast.status.${a.status}`)
  const body = a.response && a.status !== 'withdrawn' ? t('applications.toast.withResponse', { status, response: a.response }) : status
  return { title, body }
}
