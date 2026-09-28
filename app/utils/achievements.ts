import { z } from 'zod'
// Relativ importiert, damit Tests die Datei ohne Nuxt laden können.
import { currentLocale, intlLocale, t } from './i18n'
import type { IconName } from './icons'

// Launcher-Erfolge (TRS API §31): Katalog mit Kategorien, Fortschritt, Punkten und
// Belohnungen. Der Kern säubert alles; hier nur Schema, Gruppierung, Texte und
// die Entscheidung, was ein „Jetzt tragen“ bei einer Belohnung bedeutet.
// Getestet in tests/achievements.test.ts.

export const achievementCategories = ['playtime', 'launcher', 'community', 'secret'] as const
export type AchievementCategory = (typeof achievementCategories)[number]
export const achievementRarities = ['common', 'uncommon', 'rare', 'epic', 'legendary'] as const
export type AchievementRarity = (typeof achievementRarities)[number]
export const achievementFilters = ['all', 'unlocked', 'locked'] as const
export type AchievementFilter = (typeof achievementFilters)[number]
export const achievementUnits = ['count', 'minutes', 'days'] as const
export type AchievementUnit = (typeof achievementUnits)[number]

const id = z.string().regex(/^[a-z0-9][a-z0-9_.-]{0,63}$/)
const uuid = z.string().regex(/^[0-9a-f]{32}$/)
const at = z.string().max(40).nullable().catch(null).default(null)
const count = z.number().int().nonnegative()
const langText = z.string().max(300).optional().catch(undefined)
const localText = z.object({ en: langText, de: langText, es: langText })
export type LocalText = z.infer<typeof localText>

/** Belohnung: die API schickt nur Art und ID, den Namen suchen wir in den eigenen Listen. */
export const achievementRewardSchema = z.object({
  kind: z.enum(['cape', 'cosmetic']),
  id: z.string().regex(/^[a-z0-9][a-z0-9_-]{0,39}$/),
  name: z.string().max(48).nullable().catch(null).default(null),
})
export type AchievementReward = z.infer<typeof achievementRewardSchema>

export const achievementSchema = z.object({
  id,
  category: z.enum(achievementCategories),
  secret: z.boolean().catch(false).default(false),
  /** Geheim und (für mich) gesperrt: ohne Titel, Beschreibung, Belohnung, Ziel – „???“. */
  hidden: z.boolean().catch(false).default(false),
  title: localText.nullable().catch(null).default(null),
  description: localText.nullable().catch(null).default(null),
  icon: z.string().max(40).catch('trophy').default('trophy'),
  points: count.max(100_000).catch(0).default(0),
  rarity: z.enum(achievementRarities).catch('common').default('common'),
  goal: z.number().int().positive().nullable().catch(null).default(null),
  unit: z.enum(achievementUnits).nullable().catch(null).default(null),
  /** Vom Server gezählt (`false` = vom Launcher gemeldet). */
  verified: z.boolean().catch(false).default(false),
  reward: achievementRewardSchema.nullable().catch(null).default(null),
  order: z.number().int().catch(0).default(0),
})
export type Achievement = z.infer<typeof achievementSchema>

export const unlockedAchievementSchema = z.object({ id, at })
export type UnlockedAchievement = z.infer<typeof unlockedAchievementSchema>

/** Liste: kaputte Einträge fallen weg, statt alles scheitern zu lassen. */
function tolerantList<T extends z.ZodTypeAny>(item: T, max: number) {
  return z
    .array(z.unknown())
    .max(max)
    .transform((list) => list.flatMap((x) => {
      const r = item.safeParse(x)
      return r.success ? [r.data as z.infer<T>] : []
    }))
}

export const myAchievementsSchema = z.object({
  achievements: tolerantList(achievementSchema, 500),
  unlocked: tolerantList(unlockedAchievementSchema, 500),
  progress: z.record(z.string(), count).catch({}).default({}),
  points: count.catch(0).default(0),
  /** Punkte aller Erfolge zusammen. */
  totalPoints: count.catch(0).default(0),
  /** Freunde dürfen meine Erfolge sehen. */
  visibleToFriends: z.boolean().catch(true).default(true),
})
export type MyAchievements = z.infer<typeof myAchievementsSchema>

export const playerAchievementsSchema = z.object({
  uuid,
  /** Der Freund zeigt seine Erfolge nicht. */
  hidden: z.boolean().catch(false).default(false),
  unlocked: tolerantList(unlockedAchievementSchema, 500),
  points: count.catch(0).default(0),
})
export type PlayerAchievements = z.infer<typeof playerAchievementsSchema>

/** Live-Ereignis (`trs-live`). `reward` = gerade gewährt (`null`: keine oder noch nicht verfügbar). */
export const achievementEventSchemas = [
  z.object({
    type: z.literal('achievement_unlocked'),
    achievement: achievementSchema,
    at: z.string().max(40).nullable(),
    reward: achievementRewardSchema.nullable(),
  }),
] as const
export type AchievementUnlockedEvent = z.infer<(typeof achievementEventSchemas)[0]>

// --- Texte ---------------------------------------------------------------------------------

/** Text in der Launcher-Sprache, sonst Englisch, sonst irgendeiner. */
export function achievementText(text: LocalText | null | undefined, lang: string = currentLocale.value): string | null {
  if (!text) return null
  const base = lang.split('-')[0] as 'en' | 'de' | 'es'
  return text[base] ?? text.en ?? text.de ?? text.es ?? null
}

/** Titel – geheim und gesperrt (oder beim Freund, aber bei mir unbekannt): „Geheimer Erfolg“. */
export function achievementTitle(a: Pick<Achievement, 'title' | 'hidden'> | null | undefined): string {
  return (a && !a.hidden ? achievementText(a.title) : null) ?? t('achievements.secret.title')
}

export function achievementDescription(a: Pick<Achievement, 'description' | 'hidden'> | null | undefined): string {
  return (a && !a.hidden ? achievementText(a.description) : null) ?? t('achievements.secret.description')
}

export function isHiddenAchievement(a: Pick<Achievement, 'hidden' | 'title'>): boolean {
  return a.hidden || !a.title
}

export function achievementRarityLabel(r: AchievementRarity): string {
  return t(`achievements.rarity.${r}`)
}

export function achievementCategoryLabel(c: AchievementCategory): string {
  return t(`achievements.category.${c}`)
}

/** Symbol-Schlüssel der API (§31.1) → Linien-Icons des Launchers. */
const iconAliases: Record<string, IconName> = {
  rocket: 'play', repeat: 'sync', clock: 'behavior', hourglass: 'behavior', timer: 'behavior', trophy: 'trophy',
  flame: 'flame', calendar: 'calendar', puzzle: 'puzzle', boxes: 'library', package: 'library', share: 'link',
  download: 'install', film: 'clips', clapperboard: 'clips', globe: 'network', cape: 'skins', hat: 'crown',
  camera: 'screenshots', wrench: 'wrench', truck: 'import', 'user-plus': 'userPlus', users: 'friends', message: 'chat',
  messages: 'chat', bug: 'bug', 'bug-off': 'bug', 'thumbs-up': 'thumbsUp', vote: 'check', lightbulb: 'lightbulb',
  circuit: 'trs', duck: 'smile', moon: 'moon', key: 'key', crown: 'crown', secret: 'lock', lock: 'lock', star: 'star',
}

/** Was als Symbol erscheint: Linien-Icon oder (kurzes) Emoji, sonst der Pokal. */
export function achievementIcon(icon: string): { icon: IconName } | { emoji: string } {
  const alias = iconAliases[icon.trim().toLowerCase()]
  if (alias) return { icon: alias }
  // Emoji: kurz, ohne ASCII (keine beliebigen Texte als Symbol).
  if (icon && [...icon].length <= 4 && !/[\x20-\x7e]/.test(icon)) return { emoji: icon }
  return { icon: 'trophy' }
}

function formatInt(n: number): string {
  return new Intl.NumberFormat(intlLocale(), { maximumFractionDigits: 0 }).format(n)
}

/** „134 / 600“ – Spielzeit in Stunden (die API zählt Minuten), Tage mit Einheit. */
export function achievementProgressText(current: number, goal: number, unit: AchievementUnit | null): string {
  if (unit === 'minutes') {
    const hours = (m: number) => new Intl.NumberFormat(intlLocale(), { maximumFractionDigits: m % 60 === 0 || m >= 600 ? 0 : 1 }).format(m / 60)
    return t('achievements.unit.hours', { current: hours(Math.floor(current)), goal: hours(goal) })
  }
  if (unit === 'days') return t('achievements.unit.days', { current: formatInt(current), goal: formatInt(goal) }, goal)
  return t('achievements.progress', { current: formatInt(current), goal: formatInt(goal) })
}

// --- Ansicht -------------------------------------------------------------------------------

export interface AchievementItem {
  achievement: Achievement
  unlocked: boolean
  at: string | null
  /** Fortschritt (nur mit Ziel). */
  progress: number | null
  /** 0–1 (freigeschaltet = 1). */
  ratio: number
}

export interface AchievementGroup {
  category: AchievementCategory
  items: AchievementItem[]
  unlocked: number
  total: number
}

export interface AchievementSummary {
  unlocked: number
  total: number
  points: number
  maxPoints: number
}

export function achievementItems(data: MyAchievements): AchievementItem[] {
  const done = new Map(data.unlocked.map((u) => [u.id, u.at]))
  return [...data.achievements]
    .sort((a, b) => a.order - b.order)
    .map((a) => {
      const unlocked = done.has(a.id)
      const goal = a.goal
      const progress = goal ? (unlocked ? goal : Math.min(data.progress[a.id] ?? 0, goal)) : null
      const ratio = unlocked ? 1 : goal && progress !== null ? Math.min(progress / goal, 1) : 0
      return { achievement: a, unlocked, at: done.get(a.id) ?? null, progress, ratio }
    })
}

/** Nach Kategorie gruppiert (feste Reihenfolge), gefiltert; leere Gruppen fallen weg. */
export function groupAchievements(data: MyAchievements, filter: AchievementFilter = 'all'): AchievementGroup[] {
  const items = achievementItems(data)
  return achievementCategories.flatMap((category) => {
    const all = items.filter((i) => i.achievement.category === category)
    const shown = all.filter((i) => filter === 'all' || (filter === 'unlocked' ? i.unlocked : !i.unlocked))
    if (!shown.length) return []
    return [{ category, items: shown, unlocked: all.filter((i) => i.unlocked).length, total: all.length }]
  })
}

export function summarizeAchievements(data: MyAchievements): AchievementSummary {
  const done = new Set(data.unlocked.map((u) => u.id))
  return {
    unlocked: data.achievements.filter((a) => done.has(a.id)).length,
    total: data.achievements.length,
    points: data.points,
    maxPoints: data.totalPoints || data.achievements.reduce((sum, a) => sum + a.points, 0),
  }
}

/** Freigeschaltete Erfolge eines Freundes mit meinem Katalog (unbekannt oder geheim für mich = „???“). */
export interface FriendAchievement {
  id: string
  at: string | null
  achievement: Achievement | null
}

export function friendAchievements(friend: PlayerAchievements, catalog: MyAchievements | null, limit = Infinity): FriendAchievement[] {
  const byId = new Map((catalog?.achievements ?? []).map((a) => [a.id, a]))
  return [...friend.unlocked]
    .sort((a, b) => (b.at ?? '').localeCompare(a.at ?? ''))
    .slice(0, limit)
    .map((u) => ({ id: u.id, at: u.at, achievement: byId.get(u.id) ?? null }))
}

/** Freigeschaltet übernehmen (Live-Ereignis): Katalog-Eintrag ersetzen, Punkte dazu – `false`, wenn schon bekannt. */
export function applyAchievementUnlock(data: MyAchievements, e: Pick<AchievementUnlockedEvent, 'achievement' | 'at'>): boolean {
  const a = e.achievement
  const known = data.unlocked.some((u) => u.id === a.id)
  const index = data.achievements.findIndex((x) => x.id === a.id)
  if (index >= 0) data.achievements[index] = a
  else data.achievements.push(a)
  if (known) return false
  data.unlocked.push({ id: a.id, at: e.at })
  data.points += a.points
  if (a.goal) data.progress[a.id] = a.goal
  // Meta-Erfolg „alle Geheimnisse“ zählt geheime Erfolge mit.
  if (a.secret) {
    const meta = data.achievements.find((x) => x.id === 'all_secrets' && x.goal)
    if (meta?.goal) data.progress[meta.id] = Math.min((data.progress[meta.id] ?? 0) + 1, meta.goal)
  }
  return true
}

// --- Belohnungen ---------------------------------------------------------------------------

const isEmote = (id: string) => /^emote[-_]/.test(id)

/** Anzeigename einer Belohnung: aus den eigenen Listen, sonst aus der ID („secret-crown“ → „Secret Crown“). */
export function achievementRewardName(reward: Pick<AchievementReward, 'id' | 'name'>, names: Readonly<Record<string, string>> = {}): string {
  const known = reward.name ?? names[reward.id]
  if (known) return known
  const base = isEmote(reward.id) ? reward.id.replace(/^emote[-_]/, '') : reward.id
  return base
    .split(/[-_]+/)
    .filter(Boolean)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ')
}

/**
 * Was „Jetzt tragen“ bei einer Belohnung tut: Umhang anlegen, Kopf-Kosmetik
 * aufsetzen, beim Emote nur ein Hinweis (Emote-Rad im TRS Client). Fehlt das
 * Ding noch in meiner Liste (z. B. noch nicht auf dem Server), gibt es keinen Knopf.
 */
export type AchievementRewardAction = 'cape' | 'hat' | 'emote' | null

export function achievementRewardAction(
  reward: Pick<AchievementReward, 'kind' | 'id'> | null,
  owned: { capes: readonly string[]; hats: readonly string[] },
): AchievementRewardAction {
  if (!reward) return null
  if (reward.kind === 'cape') return owned.capes.includes(reward.id) ? 'cape' : null
  if (owned.hats.includes(reward.id)) return 'hat'
  if (isEmote(reward.id)) return 'emote'
  return null
}

export function achievementRewardLabel(reward: AchievementReward, names: Readonly<Record<string, string>> = {}): string {
  const name = achievementRewardName(reward, names)
  return reward.kind === 'cape'
    ? t('achievements.reward.cape', { name })
    : isEmote(reward.id)
      ? t('achievements.reward.emote', { name })
      : t('achievements.reward.cosmetic', { name })
}

/** Titel + Text der Benachrichtigung. */
export function achievementToastText(e: AchievementUnlockedEvent, names: Readonly<Record<string, string>> = {}): { title: string; body: string } {
  const name = achievementTitle(e.achievement)
  const points = t('achievements.points', { points: e.achievement.points }, e.achievement.points)
  const body = e.reward
    ? t('achievements.toast.bodyReward', { name, points, reward: achievementRewardName(e.reward, names) })
    : t('achievements.toast.body', { name, points })
  return { title: t('achievements.toast.title'), body }
}
