import type { ChangelogEntry } from './changelog'

// Reine Hilfen für den Update-Beitrag im Launcher (Dialog, Karte, „Was ist neu“) – ohne Vue/Nuxt, damit
// die Tests sie direkt prüfen können.

/** Blog der Website – ein Beitrag je Launcher-Version unter /blog/<version> (siehe Website pages/blog/[version].vue). */
export const BLOG_BASE_URL = 'https://trs-launcher.theredstonee.de/blog'
/** Sprachen der Website; andere Launcher-Sprachen überlassen der Website die Wahl. */
const BLOG_LANGS = ['en', 'de', 'es'] as const

/** Adresse des Blog-Beitrags zu einer Version, in der Launcher-Sprache, wenn die Website sie kennt. */
export function blogPostUrl(version: string, locale: string): string {
  const v = version.replace(/^v/, '')
  if (!/^\d+\.\d+\.\d+(?:-[\w.]+)?$/.test(v)) return BLOG_BASE_URL
  const lang = (BLOG_LANGS as readonly string[]).includes(locale) ? `?lang=${locale}` : ''
  return `${BLOG_BASE_URL}/${v}${lang}`
}

/** Changelog-Sprache für eine Launcher-Sprache: Deutsch → deutscher Teil, sonst Englisch. */
export function postLang(locale: string): 'en' | 'de' {
  return locale === 'de' ? 'de' : 'en'
}

/** Update-Name in der Sprache des Lesers, sonst `null` (dann zeigt die Oberfläche „Version x“). */
export function postTitle(entry: Pick<ChangelogEntry, 'title'>, locale: string): string | null {
  return entry.title ? entry.title[postLang(locale)] : null
}

/** Nächstes/vorheriges Bild einer Galerie mit `length` Bildern – am Ende geht es vorn weiter. */
export function stepShot(index: number, delta: number, length: number): number {
  if (length <= 0) return 0
  return (((index + delta) % length) + length) % length
}

/** Tasten der Galerie: ←/→ blättern, Pos1/Ende springen; alles andere `null`. */
export function shotKey(key: string, index: number, length: number): number | null {
  if (length <= 1) return null
  switch (key) {
    case 'ArrowLeft':
      return stepShot(index, -1, length)
    case 'ArrowRight':
      return stepShot(index, 1, length)
    case 'Home':
      return 0
    case 'End':
      return length - 1
    default:
      return null
  }
}
