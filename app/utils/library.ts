import type { Instance, LoaderKind } from '~/types'
// Relativ importiert, damit Tests die Helfer ohne Nuxt laden können.
import { compareText } from './format'
import { t } from './i18n'

// Sortieren, Filtern und Gruppieren der Bibliothek – reine Funktionen, getestet in tests/library.test.ts.

export type LibrarySort = 'played' | 'name' | 'created' | 'playtime' | 'version'
export type LibraryGroupBy = 'none' | 'loader' | 'version' | 'custom'

export interface LibraryFilter {
  query: string
  loaders: LoaderKind[]
  /** Hauptversionen wie `1.21` oder `26.1` */
  versions: string[]
}

export interface LibraryGroup {
  key: string
  label: string
  items: Instance[]
}

/** Sortierungen in Menü-Reihenfolge – Texte unter `library.sort.*`. */
export const librarySorts: LibrarySort[] = ['played', 'name', 'created', 'playtime', 'version']

/** Gruppierungen in Menü-Reihenfolge – Texte unter `library.groupBy.*`. */
export const libraryGroupBys: LibraryGroupBy[] = ['none', 'loader', 'version', 'custom']

const LOADER_ORDER: LoaderKind[] = ['vanilla', 'fabric', 'quilt', 'forge', 'neoforge']
const LOADER_NAMES: Record<LoaderKind, string> = {
  vanilla: 'Vanilla',
  fabric: 'Fabric',
  quilt: 'Quilt',
  forge: 'Forge',
  neoforge: 'NeoForge',
}

function numbers(v: string): number[] {
  return (v.match(/\d+/g) ?? []).map(Number)
}

/**
 * Neuere Version zuerst (negativ, wenn `a` neuer ist). Mit `order` (Index im
 * Mojang-Manifest, 0 = neueste) exakt, sonst numerisch – `26.1` ist neuer als `1.21.11`.
 */
export function compareGameVersions(a: string, b: string, order?: Map<string, number>): number {
  const ia = order?.get(a)
  const ib = order?.get(b)
  if (ia !== undefined && ib !== undefined) return ia - ib
  const na = numbers(a)
  const nb = numbers(b)
  for (let i = 0; i < Math.max(na.length, nb.length); i++) {
    const d = (nb[i] ?? -1) - (na[i] ?? -1)
    if (d !== 0) return d
  }
  return a.localeCompare(b)
}

/** `1.21.11` → `1.21`, `26.1.2` → `26.1`, Snapshots bleiben wie sie sind. */
export function majorVersion(v: string): string {
  const m = /^(\d+\.\d+)/.exec(v)
  return m ? m[1]! : v
}

const byName = (a: Instance, b: Instance) => compareText(a.name, b.name)
const time = (iso: string | null) => (iso ? new Date(iso).getTime() : 0)

export function sortInstances(list: Instance[], sort: LibrarySort, order?: Map<string, number>): Instance[] {
  const out = [...list]
  const cmp: Record<LibrarySort, (a: Instance, b: Instance) => number> = {
    played: (a, b) => time(b.lastPlayed) - time(a.lastPlayed),
    name: () => 0,
    created: (a, b) => time(b.createdAt) - time(a.createdAt),
    playtime: (a, b) => b.totalPlaySeconds - a.totalPlaySeconds,
    version: (a, b) => compareGameVersions(a.gameVersion, b.gameVersion, order),
  }
  return out.sort((a, b) => cmp[sort](a, b) || byName(a, b))
}

export function filterInstances(list: Instance[], filter: LibraryFilter): Instance[] {
  const needle = filter.query.trim().toLowerCase()
  return list.filter(
    (i) =>
      (!filter.loaders.length || filter.loaders.includes(i.loader.kind)) &&
      (!filter.versions.length || filter.versions.includes(majorVersion(i.gameVersion))) &&
      (!needle ||
        `${i.name} ${i.gameVersion} ${LOADER_NAMES[i.loader.kind]} ${i.group ?? ''}`.toLowerCase().includes(needle)),
  )
}

/** Gruppiert die (bereits sortierte) Liste; die Reihenfolge innerhalb bleibt. Beschriftungen in der eingestellten Sprache. */
export function groupInstances(list: Instance[], groupBy: LibraryGroupBy, order?: Map<string, number>): LibraryGroup[] {
  if (groupBy === 'none') return [{ key: 'all', label: '', items: list }]
  const groups = new Map<string, LibraryGroup>()
  const add = (key: string, label: string, item: Instance) => {
    const g = groups.get(key) ?? { key, label, items: [] }
    g.items.push(item)
    groups.set(key, g)
  }
  for (const i of list) {
    if (groupBy === 'loader') add(i.loader.kind, LOADER_NAMES[i.loader.kind], i)
    else if (groupBy === 'version') add(majorVersion(i.gameVersion), `Minecraft ${majorVersion(i.gameVersion)}`, i)
    else add(i.group ? `g:${i.group}` : 'none', i.group ?? t('library.ungrouped'), i)
  }
  const result = [...groups.values()]
  if (groupBy === 'loader') result.sort((a, b) => LOADER_ORDER.indexOf(a.key as LoaderKind) - LOADER_ORDER.indexOf(b.key as LoaderKind))
  else if (groupBy === 'version') result.sort((a, b) => compareGameVersions(a.items[0]!.gameVersion, b.items[0]!.gameVersion, order) || compareGameVersions(a.key, b.key))
  else result.sort((a, b) => (a.key === 'none' ? 1 : b.key === 'none' ? -1 : compareText(a.label, b.label)))
  return result
}

/** Alle eigenen Gruppen, alphabetisch. */
export function customGroups(list: Instance[]): string[] {
  return [...new Set(list.map((i) => i.group).filter((g): g is string => !!g))].sort(compareText)
}
