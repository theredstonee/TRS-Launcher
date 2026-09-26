// Dateibrowser der Instanzseite: Sortieren, Filtern, Symbole je Dateiart und
// bekannte Minecraft-Ordner, Brotkrumen. Reine Funktionen (Tests: tests/files.test.ts).
import type { FileEntry } from '../types'

export type FileSortKey = 'name' | 'size' | 'created' | 'modified'
export interface FileSort {
  key: FileSortKey
  desc: boolean
}

/** Symbol-Art eines Eintrags (bestimmt Icon und Farbe). */
export type FileKind =
  | 'folder'
  | 'mods'
  | 'config'
  | 'saves'
  | 'resourcepacks'
  | 'shaderpacks'
  | 'screenshots'
  | 'logs'
  | 'crash'
  | 'datapacks'
  | 'backups'
  | 'archive'
  | 'jar'
  | 'image'
  | 'text'
  | 'data'
  | 'audio'
  | 'video'
  | 'nbt'
  | 'file'

/** Bekannte Ordner im Spielordner (nur auf oberster Ebene). */
const KNOWN_FOLDERS: Record<string, FileKind> = {
  mods: 'mods',
  config: 'config',
  defaultconfigs: 'config',
  saves: 'saves',
  resourcepacks: 'resourcepacks',
  texturepacks: 'resourcepacks',
  shaderpacks: 'shaderpacks',
  screenshots: 'screenshots',
  logs: 'logs',
  'crash-reports': 'crash',
  datapacks: 'datapacks',
  backups: 'backups',
}

const EXTENSIONS: Record<string, FileKind> = {
  zip: 'archive',
  gz: 'archive',
  '7z': 'archive',
  rar: 'archive',
  tar: 'archive',
  mrpack: 'archive',
  jar: 'jar',
  disabled: 'jar',
  png: 'image',
  jpg: 'image',
  jpeg: 'image',
  gif: 'image',
  webp: 'image',
  bmp: 'image',
  txt: 'text',
  log: 'text',
  md: 'text',
  cfg: 'text',
  conf: 'text',
  properties: 'text',
  ini: 'text',
  lang: 'text',
  json: 'data',
  json5: 'data',
  toml: 'data',
  yml: 'data',
  yaml: 'data',
  mcmeta: 'data',
  snbt: 'data',
  csv: 'data',
  ogg: 'audio',
  mp3: 'audio',
  wav: 'audio',
  mp4: 'video',
  webm: 'video',
  mkv: 'video',
  dat: 'nbt',
  nbt: 'nbt',
  mca: 'nbt',
  schem: 'nbt',
  schematic: 'nbt',
  litematic: 'nbt',
}

export function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot > 0 ? name.slice(dot + 1).toLowerCase() : ''
}

/** Art eines Eintrags; `atRoot` = direkt im Spielordner (dort gibt es die bekannten Ordner). */
export function fileKind(entry: Pick<FileEntry, 'name' | 'dir'>, atRoot: boolean): FileKind {
  if (entry.dir) return (atRoot && KNOWN_FOLDERS[entry.name.toLowerCase()]) || 'folder'
  if (entry.name.toLowerCase().endsWith('.log.gz')) return 'text'
  return EXTENSIONS[extensionOf(entry.name)] ?? 'file'
}

/** Ist das ein bekannter Minecraft-Ordner (eigene Beschriftung/Farbe)? */
export function isKnownFolder(kind: FileKind): boolean {
  return kind !== 'folder' && Object.values(KNOWN_FOLDERS).includes(kind)
}

function time(iso: string | null): number {
  return iso ? Date.parse(iso) || 0 : 0
}

/**
 * Ordner immer zuerst, danach nach Schlüssel; gleiche Werte nach Name.
 * `collator` vergleicht Namen natürlich („Welt 2“ vor „Welt 10“).
 */
export function sortFiles(entries: readonly FileEntry[], sort: FileSort, collator: Intl.Collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })): FileEntry[] {
  const dir = sort.desc ? -1 : 1
  return [...entries].sort((a, b) => {
    if (a.dir !== b.dir) return a.dir ? -1 : 1
    let diff = 0
    if (sort.key === 'size') diff = a.size - b.size
    else if (sort.key === 'created') diff = time(a.created) - time(b.created)
    else if (sort.key === 'modified') diff = time(a.modified) - time(b.modified)
    if (diff === 0) diff = collator.compare(a.name, b.name)
    return diff * dir
  })
}

/** Einfacher Teilstring-Filter (kein RegExp aus Nutzereingaben). */
export function filterFiles(entries: readonly FileEntry[], query: string): FileEntry[] {
  const needle = query.trim().toLowerCase()
  if (!needle) return [...entries]
  return entries.filter((e) => e.name.toLowerCase().includes(needle))
}

export interface Crumb {
  name: string
  /** Relativer Pfad bis hierher ("" = Spielordner). */
  path: string
}

export function breadcrumbs(path: string): Crumb[] {
  const parts = path.split('/').filter(Boolean)
  return parts.map((name, i) => ({ name, path: parts.slice(0, i + 1).join('/') }))
}

export function joinPath(dir: string, name: string): string {
  return dir ? `${dir}/${name}` : name
}

export function parentPath(path: string): string {
  const i = path.lastIndexOf('/')
  return i < 0 ? '' : path.slice(0, i)
}

/**
 * Prüft einen neuen Namen wie der Kern (schnelle Rückmeldung im Dialog –
 * entscheidend bleibt die Prüfung in Rust). Liefert einen Fehler-Schlüssel oder `null`.
 */
export function nameProblem(name: string): 'empty' | 'invalid' | null {
  const n = name.trim()
  if (!n) return 'empty'
  if (n === '.' || n === '..' || n.endsWith('.') || new TextEncoder().encode(n).length > 255) return 'invalid'
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f/\\:*?"<>|]/.test(n)) return 'invalid'
  if (/^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\..*)?$/i.test(n)) return 'invalid'
  return null
}

/** Auswahl mit Umschalt (Bereich) und Strg (einzeln umschalten) – wie im Explorer. */
export function nextSelection(
  current: ReadonlySet<string>,
  ordered: readonly string[],
  clicked: string,
  anchor: string | null,
  mods: { shift: boolean; toggle: boolean },
): Set<string> {
  if (mods.shift && anchor !== null) {
    const a = ordered.indexOf(anchor)
    const b = ordered.indexOf(clicked)
    if (a >= 0 && b >= 0) {
      const [from, to] = a < b ? [a, b] : [b, a]
      const range = new Set(mods.toggle ? current : [])
      for (const name of ordered.slice(from, to + 1)) range.add(name)
      return range
    }
  }
  if (mods.toggle) {
    const next = new Set(current)
    if (next.has(clicked)) next.delete(clicked)
    else next.add(clicked)
    return next
  }
  return new Set([clicked])
}
