import { z } from 'zod'
import type { Platform } from '~/types'
// Relativ importiert, damit Tests die Datei ohne Nuxt laden können.
import type { MessageKey } from './i18n'

// Empfohlene Modpacks (Startseite + Team-Bereich): Schemas für das, was der Kern liefert
// (beim Empfang geprüft), welche Texte eine Folie zeigt, der Zustand eines Eintrags in der
// Team-Liste und kleine Helfer (Reihenfolge, Zeitraum, Deep Link). Getestet in tests/featured.test.ts.

/** Länge der Team-Texte (wie die API). */
export const FEATURED_TITLE_MAX = 60
/** Länge des Team-Textes. */
export const FEATURED_TEXT_MAX = 200
/** Das Karussell blättert alle 7 s weiter (nicht bei Hover, Fokus, verstecktem Fenster, reduzierter Bewegung). */
export const FEATURED_ROTATE_MS = 7000

/** Abzeichen einer Folie. */
export const featuredBadges = ['recommended', 'new', 'event'] as const
/** Abzeichen als Typ. */
export type FeaturedBadge = (typeof featuredBadges)[number]

const httpsUrl = z.string().max(1024).regex(/^https?:\/\/[^\s"'<>\\]+$/)
const time = z.string().max(40).nullable().catch(null).default(null)
const teamText = z.object({
  title: z.string().max(FEATURED_TITLE_MAX + 1).nullable().catch(null).default(null),
  text: z.string().max(FEATURED_TEXT_MAX + 1).nullable().catch(null).default(null),
})
const emptyTexts = () => ({ en: { title: null, text: null }, de: { title: null, text: null } })

const featuredPackBase = z.object({
  id: z.string().regex(/^[A-Za-z0-9_-]{1,64}$/),
  source: z.enum(['modrinth', 'curseforge']),
  projectId: z.string().regex(/^[A-Za-z0-9_-]{1,64}$/),
  slug: z.string().max(100).catch(''),
  url: z.string().max(2048).regex(/^https:\/\/(www\.)?(modrinth\.com|curseforge\.com)(\/|$)/),
  title: z.string().min(1).max(160),
  summary: z.string().max(400).catch(''),
  author: z.string().max(64).nullable().catch(null).default(null),
  downloads: z.number().nonnegative().catch(0).default(0),
  loaders: z.array(z.string().max(32)).max(8).catch([]).default([]),
  gameVersions: z.array(z.string().max(32)).max(32).catch([]).default([]),
  iconUrl: httpsUrl.nullable().catch(null).default(null),
  bannerUrl: httpsUrl.nullable().catch(null).default(null),
  badge: z.enum(featuredBadges).nullable().catch(null).default(null),
  texts: z.object({ en: teamText, de: teamText }).catch(emptyTexts()).default(emptyTexts()),
  pinned: z.boolean().catch(false).default(false),
  startsAt: time,
  endsAt: time,
  updatedAt: time,
})
/** Die Projekt-ID muss zur Quelle passen (CurseForge: Zahl). */
const idFitsSource = (p: { source: string; projectId: string }) => p.source === 'modrinth' || /^[1-9]\d{0,11}$/.test(p.projectId)

/** Eine Folie der Startseite. */
export const featuredPackSchema = featuredPackBase.refine(idFitsSource)

/** Eintrag der Team-Liste: dazu Zustand und wer ihn wann angelegt hat. */
export const adminFeaturedPackSchema = featuredPackBase
  .extend({
    active: z.boolean().catch(false).default(false),
    createdBy: z.string().max(64).catch('').default(''),
    createdAt: time,
    refreshedAt: time,
  })
  .refine(idFitsSource)

/** Liste der Startseite: kaputte Einträge fallen einzeln weg statt die ganze Liste. */
export const featuredListSchema = z
  .array(z.unknown())
  .max(20)
  .transform((list) => list.flatMap((item) => {
    const r = featuredPackSchema.safeParse(item)
    return r.success ? [r.data] : []
  }))

export type FeaturedPack = z.infer<typeof featuredPackSchema>
export type AdminFeaturedPack = z.infer<typeof adminFeaturedPackSchema>

/** Was das Team an einem Eintrag ändert (fehlende Felder bleiben, `null` leert). */
export interface FeaturedPatch {
  texts?: { en: { title: string | null; text: string | null }; de: { title: string | null; text: string | null } }
  badge?: FeaturedBadge | null
  pinned?: boolean
  startsAt?: string | null
  endsAt?: string | null
}

// --- Folie -----------------------------------------------------------------------------

const deLike = (locale: string) => locale === 'de'

/** Titel der Folie: eigener Titel des Teams in der UI-Sprache (Deutsch → de, sonst en), sonst der Projekttitel. */
export function featuredTitle(pack: Pick<FeaturedPack, 'title' | 'texts'>, locale: string): string {
  const own = deLike(locale) ? (pack.texts.de.title ?? pack.texts.en.title) : pack.texts.en.title
  return own?.trim() || pack.title
}

/** Text der Folie: Deutsch → de, en, Zusammenfassung; andere Sprachen → en, Zusammenfassung. */
export function featuredText(pack: Pick<FeaturedPack, 'summary' | 'texts'>, locale: string): string {
  const own = deLike(locale) ? (pack.texts.de.text ?? pack.texts.en.text) : pack.texts.en.text
  return own?.trim() || pack.summary
}

/** Neueste Minecraft-Version (die Liste ist neueste zuerst). */
export function featuredVersion(pack: Pick<FeaturedPack, 'gameVersions'>): string | null {
  return pack.gameVersions[0] ?? null
}

export const featuredBadgeKeys: Record<FeaturedBadge, MessageKey> = {
  recommended: 'featured.badges.recommended',
  new: 'featured.badges.new',
  event: 'featured.badges.event',
}

/** Nächste Folie (rundum). */
export function featuredStep(index: number, delta: number, count: number): number {
  if (count <= 0) return 0
  return (((index + delta) % count) + count) % count
}

/** Wischgeste: genug waagerecht (und mehr als senkrecht) → -1/1, sonst 0. */
export function swipeDirection(dx: number, dy: number, threshold = 40): -1 | 0 | 1 {
  if (Math.abs(dx) < threshold || Math.abs(dx) < Math.abs(dy) * 1.2) return 0
  return dx < 0 ? 1 : -1
}

// --- Team-Liste --------------------------------------------------------------------------

export type FeaturedStatus = 'active' | 'scheduled' | 'expired'

/** Zustand eines Eintrags nach seinem Zeitraum. */
export function featuredStatus(pack: Pick<FeaturedPack, 'startsAt' | 'endsAt'>, now = Date.now()): FeaturedStatus {
  const start = pack.startsAt ? Date.parse(pack.startsAt) : NaN
  const end = pack.endsAt ? Date.parse(pack.endsAt) : NaN
  if (!Number.isNaN(end) && end <= now) return 'expired'
  if (!Number.isNaN(start) && start > now) return 'scheduled'
  return 'active'
}

/** Element von `from` nach `to` verschieben (neue Liste). */
export function featuredMove<T>(list: readonly T[], from: number, to: number): T[] {
  if (from === to || from < 0 || to < 0 || from >= list.length || to >= list.length) return [...list]
  const next = [...list]
  const [item] = next.splice(from, 1)
  next.splice(to, 0, item!)
  return next
}

/** ISO → Wert für `<input type="datetime-local">` (Ortszeit), leer bei `null`. */
export function isoToLocalInput(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** `datetime-local` (Ortszeit) → ISO in UTC; leer/ungültig → `null`. */
export function localInputToIso(value: string): string | null {
  if (!value.trim()) return null
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? null : d.toISOString()
}

/** Ein eingefügter Link sieht nach Modrinth/CurseForge aus (die genaue Prüfung machen Kern und Server). */
export function looksLikePackLink(input: string): boolean {
  const s = input.trim()
  if (!s || s.length > 512) return false
  try {
    const url = new URL(/^[a-z]+:\/\//i.test(s) ? s : `https://${s}`)
    return /^https?:$/.test(url.protocol) && /^(www\.)?(modrinth\.com|curseforge\.com)$/.test(url.hostname)
  } catch {
    return false
  }
}

// --- Deep Link ---------------------------------------------------------------------------

/** `modrinth/<ID>` bzw. `curseforge/<Zahl>` aus `trs-launcher://modpack/…` (vom Kern geprüft, hier noch einmal). */
export function parseModpackLink(value: unknown): { platform: Platform; projectId: string } | null {
  if (typeof value !== 'string') return null
  const m = /^(modrinth|curseforge)\/([A-Za-z0-9_-]{1,64})$/.exec(value)
  if (!m) return null
  const platform = m[1] as Platform
  const projectId = m[2]!
  if (platform === 'curseforge' && !/^[1-9]\d{0,11}$/.test(projectId)) return null
  return { platform, projectId }
}
