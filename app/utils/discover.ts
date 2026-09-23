import type { ModrinthHit, ProjectKind, SortIndex } from '~/types'
// Relativ importiert, damit Tests die Helfer ohne Nuxt laden können.
import { t } from './i18n'

// Hilfen für die Entdecken-Seite (Sortierung, Seiten, Tags).

/** Sortierungen der Suche; Anzeige über `sortLabel`. */
export const sortIndexes: SortIndex[] = ['relevance', 'downloads', 'follows', 'newest', 'updated']

export function sortLabel(index: SortIndex): string {
  return t(`modrinth.sort.${index}`)
}

export const pageSizes = [20, 50, 100] as const

/** Modrinth liefert höchstens bis Offset 10 000. */
export const MAX_SEARCH_OFFSET = 10_000

/** Modrinth-Projekttyp, unter dem die Kategorien einer Art geführt werden. */
export function categoryProjectType(kind: ProjectKind): string {
  if (kind === 'shaderpack') return 'shader'
  // Datenpakete nutzen Modrinths Mod-Kategorien.
  if (kind === 'datapack') return 'mod'
  return kind
}

/** Überschriften der Kategorie-Gruppen aus Modrinths Tag-API → Übersetzungsschlüssel. */
export const categoryHeaderKeys: Record<string, 'categories' | 'features' | 'resolutions' | 'performanceImpact'> = {
  categories: 'categories',
  features: 'features',
  resolutions: 'resolutions',
  'performance impact': 'performanceImpact',
}

/** Anzeigename einer Kategorie-Gruppe (unbekannte bleiben, wie sie sind). */
export function categoryHeaderLabel(header: string): string {
  const key = categoryHeaderKeys[header]
  return key ? t(`modrinth.categoryHeader.${key}`) : header
}

/** Wo läuft das Projekt? `null` = unbekannt. */
export function environmentLabel(hit: Pick<ModrinthHit, 'clientSide' | 'serverSide'>): string | null {
  const client = hit.clientSide === 'required' || hit.clientSide === 'optional'
  const server = hit.serverSide === 'required' || hit.serverSide === 'optional'
  if (hit.clientSide === 'required' && hit.serverSide === 'required') return t('modrinth.environment.both')
  if (client && hit.serverSide === 'unsupported') return t('modrinth.environment.client')
  if (server && hit.clientSide === 'unsupported') return t('modrinth.environment.server')
  if (client && server) return t('modrinth.environment.either')
  return null
}

/**
 * Seitenleiste wie „1 2 3 4 5 … 94“: erste und letzte Seite immer, dazu die
 * Nachbarn der aktuellen. `null` steht für die Auslassung.
 */
export function pageItems(current: number, total: number): (number | null)[] {
  if (total <= 7) return Array.from({ length: Math.max(total, 0) }, (_, i) => i + 1)
  if (current <= 4) return [1, 2, 3, 4, 5, null, total]
  if (current >= total - 3) return [1, null, total - 4, total - 3, total - 2, total - 1, total]
  return [1, null, current - 1, current, current + 1, null, total]
}

/** Wie viele Seiten sich anzeigen lassen (Modrinth-Grenze eingerechnet). */
export function pageCount(totalHits: number, limit: number): number {
  const reachable = Math.floor(MAX_SEARCH_OFFSET / limit) + 1
  return Math.max(1, Math.min(Math.ceil(totalHits / limit), reachable))
}

/**
 * Modrinths Kategorie-Icons sind SVG-Markup. Angezeigt werden sie nur als
 * `<img src="data:…">` – in Bildern führt der Browser keine Skripte aus. Der
 * Kern hat sie außerdem schon auf verdächtige Inhalte geprüft.
 */
export function svgIconUrl(svg: string, color: string): string | null {
  const trimmed = svg.trim()
  if (!trimmed.startsWith('<svg') || !/^#[0-9a-f]{3,8}$/i.test(color)) return null
  const withNs = /xmlns=/.test(trimmed) ? trimmed : trimmed.replace('<svg', '<svg xmlns="http://www.w3.org/2000/svg"')
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(withNs.replaceAll('currentColor', color))}`
}
