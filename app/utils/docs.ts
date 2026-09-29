import type { CrashKind } from '~/types'
import { currentLocale } from './i18n'

// Links in die Dokumentation auf der Website (Docus, ersetzt das GitHub-Wiki). Reine Hilfen ohne Tauri,
// damit die Tests sie direkt prüfen können – geöffnet wird wie jeder externe Link über `backend.openExternalUrl`.

/** Wurzel der Docs; Seiten liegen unter `/docs/<sprache>/<bereich>/<seite>`. */
export const DOCS_BASE_URL = 'https://trs-launcher.theredstonee.de/docs'

/** Sprachen der Docs; alle anderen Launcher-Sprachen landen auf Englisch. */
export const DOCS_LANGS = ['en', 'de', 'es'] as const
export type DocsLang = (typeof DOCS_LANGS)[number]

/** Seiten, auf die der Launcher verlinkt (Slugs sind in allen Sprachen gleich). */
export type DocsPage =
  | 'getting-started/installation'
  | 'getting-started/sign-in'
  | 'getting-started/first-instance'
  | 'launcher/instances'
  | 'launcher/mod-loaders'
  | 'launcher/mods'
  | 'launcher/modpacks'
  | 'launcher/share-modpacks'
  | 'launcher/import'
  | 'launcher/clips'
  | 'launcher/social'
  | 'launcher/achievements'
  | 'launcher/wardrobe'
  | 'launcher/settings'
  | 'launcher/updates'
  | 'client/overview'
  | 'help/crash-helper'
  | 'help/common-problems'
  | 'help/java'
  | 'help/linux'
  | 'help/logs'
  | 'help/report-a-bug'
  | 'help/privacy'

/** Docs-Sprache zu einer Launcher-Sprache: `de`, `es`, sonst `en` (auch für Beta-Sprachen wie `pt-BR`). */
export function docsLang(locale: string): DocsLang {
  const base = locale.split('-')[0]!.toLowerCase()
  return (DOCS_LANGS as readonly string[]).includes(base) ? (base as DocsLang) : 'en'
}

/**
 * Adresse einer Docs-Seite in der Launcher-Sprache; ohne Seite die Startseite der Sprache (`/docs/de/`).
 * Nur Kleinbuchstaben, Ziffern, Bindestriche und `/` – alles andere führt auf die Startseite.
 */
export function docsUrl(page?: DocsPage, locale: string = currentLocale.value): string {
  const lang = docsLang(locale)
  if (!page || !/^[a-z0-9-]+(?:\/[a-z0-9-]+)*$/.test(page)) return `${DOCS_BASE_URL}/${lang}/`
  return `${DOCS_BASE_URL}/${lang}/${page}`
}

/** Passende Hilfe-Seite zu einem Befund des Absturz-Helfers. */
export function crashDocsPage(kind: CrashKind | null | undefined): DocsPage {
  if (kind === 'wrong_java') return 'help/java'
  if (!kind || kind === 'unknown') return 'help/common-problems'
  return 'help/crash-helper'
}
