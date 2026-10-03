// Push-Benachrichtigungen der Handy-App (API §33) – Schemata und reine Helfer, getestet in tests/push.test.ts.
// Keine Nuxt-Auto-Imports, damit die Tests sie direkt laden können.
import { z } from 'zod'

/** Kategorien in der Reihenfolge der Einstellungen (wie der Server). */
export const PUSH_CATEGORIES = ['chat', 'friends', 'friend_online', 'invites', 'hosting', 'packs', 'team', 'achievements'] as const
export type PushCategory = (typeof PUSH_CATEGORIES)[number]

const category = z.string().regex(/^[a-z_]{1,32}$/)
const time = z.string().max(40).nullable()

export const pushSettingsSchema = z.object({
  enabled: z.boolean(),
  categories: z.record(category, z.boolean()),
  preview: z.boolean(),
  pushWhilePlaying: z.boolean(),
  pollFallback: z.boolean(),
})
export type PushSettings = z.output<typeof pushSettingsSchema>

export const PUSH_STATES = [
  'unsupported',
  'off',
  'signedOut',
  'permission',
  'noDistributor',
  'chooseDistributor',
  'waiting',
  'registered',
  'polling',
  'error',
] as const
export type PushStateName = (typeof PUSH_STATES)[number]

export const pushStatusSchema = z.object({
  supported: z.boolean(),
  settings: pushSettingsSchema,
  state: z.enum(PUSH_STATES),
  distributors: z.array(z.object({ id: z.string().max(255), name: z.string().max(200) })).max(20),
  distributor: z.string().max(255).nullable(),
  permission: z.enum(['granted', 'denied', 'prompt']),
  deviceId: z.string().regex(/^d[0-9a-f]{20}$/).nullable(),
  error: z.object({ code: z.string().max(100), message: z.string().max(500) }).nullable(),
  categories: z.array(category).max(32),
  defaults: z.record(category, z.boolean()),
})
export type PushStatus = z.output<typeof pushStatusSchema>

export const pushDeviceSchema = z.object({
  id: z.string().regex(/^d[0-9a-f]{20}$/),
  platform: z.enum(['android', 'ios']),
  kind: z.enum(['unifiedpush', 'poll']),
  endpointHost: z.string().max(253).nullable(),
  deviceName: z.string().max(64),
  appVersion: z.string().max(32),
  categories: z.record(category, z.boolean()),
  preview: z.boolean(),
  pushWhilePlaying: z.boolean(),
  current: z.boolean(),
  thisDevice: z.boolean(),
  createdAt: time,
  lastSeenAt: time,
  lastSuccessAt: time,
  failing: z.boolean(),
})
export type PushDevice = z.output<typeof pushDeviceSchema>

/** Wert einer Kategorie: eigene Wahl, sonst Standard des Servers. */
export function categoryOn(settings: PushSettings, defaults: Record<string, boolean>, cat: string): boolean {
  return settings.categories[cat] ?? defaults[cat] ?? cat !== 'friend_online'
}

/** Neue Schalter mit einer geänderten Kategorie (gleich dem Standard → Abweichung entfernen). */
export function withCategory(settings: PushSettings, defaults: Record<string, boolean>, cat: string, on: boolean): PushSettings {
  const categories = { ...settings.categories }
  if ((defaults[cat] ?? cat !== 'friend_online') === on) delete categories[cat]
  else categories[cat] = on
  return { ...settings, categories }
}

/** Verteiler-App ntfy zum Installieren (UnifiedPush). */
export const NTFY_FDROID = 'https://f-droid.org/packages/io.heckel.ntfy/'
export const NTFY_PLAY = 'https://play.google.com/store/apps/details?id=io.heckel.ntfy'

/** Was ein Tipp auf eine Benachrichtigung (Route aus §33.5) in der App öffnet. */
export type PushTargetAction =
  | { kind: 'route'; path: string; query?: Record<string, string> }
  | { kind: 'sanction'; id: number | null }
  | { kind: 'application'; id: string | null }
  | { kind: 'external'; url: string }

const PART = /^[A-Za-z0-9_-]{1,64}$/
const WEBSITE = 'https://trs-launcher.theredstonee.de'

export function pushTargetAction(target: unknown): PushTargetAction {
  const home: PushTargetAction = { kind: 'route', path: '/' }
  if (typeof target !== 'string' || target.length > 300 || !target.startsWith('/')) return home
  const parts = target.slice(1).split('/')
  if (parts.length > 4 || !/^[a-z]{1,24}$/.test(parts[0] ?? '') || !parts.slice(1).every((p) => PART.test(p))) return home
  const [head, a, b] = parts
  switch (head) {
    case 'chat':
      return a ? { kind: 'route', path: '/social', query: { c: a } } : { kind: 'route', path: '/social' }
    case 'friends':
      return { kind: 'route', path: '/social', query: { tab: 'friends' } }
    case 'worlds':
      return { kind: 'route', path: '/social', query: { tab: 'worlds' } }
    case 'capes':
      return { kind: 'route', path: '/skins' }
    case 'packs':
      return { kind: 'route', path: '/instances' }
    case 'achievements':
      return a ? { kind: 'route', path: '/achievements', query: { highlight: a } } : { kind: 'route', path: '/achievements' }
    case 'moderation':
      if (a === 'sanctions') return { kind: 'sanction', id: b && /^\d{1,12}$/.test(b) ? Number(b) : null }
      return { kind: 'route', path: '/social' }
    case 'team':
      return { kind: 'application', id: a === 'applications' && b ? b : null }
    case 'issues':
      return a && /^\d{1,9}$/.test(a) ? { kind: 'external', url: `${WEBSITE}/issues/${a}` } : { kind: 'external', url: `${WEBSITE}/issues` }
    case 'circuits':
      return { kind: 'external', url: `${WEBSITE}/circuits` }
    default:
      return home
  }
}
