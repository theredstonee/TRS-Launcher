import type { Instance, ContentKind, ImportSource, LaunchStage, LoaderKind, SyncItem } from '~/types'
// Relativ importiert, damit Tests die Helfer ohne Nuxt laden können.
import { intlLocale, t } from './i18n'

// Alle Formatierungen folgen der eingestellten Sprache (Intl.*). Wer sie in
// Templates oder `computed` aufruft, bekommt beim Sprachwechsel neue Texte.

export const loaderLabels: Record<LoaderKind, string> = {
  vanilla: 'Vanilla',
  fabric: 'Fabric',
  quilt: 'Quilt',
  forge: 'Forge',
  neoforge: 'NeoForge',
}

const cache = new Map<string, Intl.RelativeTimeFormat | Intl.NumberFormat | Intl.DateTimeFormat>()

function cached<T extends Intl.RelativeTimeFormat | Intl.NumberFormat | Intl.DateTimeFormat>(key: string, make: (locale: string) => T): T {
  const locale = intlLocale()
  const id = `${locale}|${key}`
  let f = cache.get(id) as T | undefined
  if (!f) {
    f = make(locale)
    cache.set(id, f)
  }
  return f
}

/** Zahl in der Schreibweise der Sprache (1.234,5 / 1,234.5). */
export function formatNumber(n: number, maximumFractionDigits = 0): string {
  return cached(`n${maximumFractionDigits}`, (l) => new Intl.NumberFormat(l, { maximumFractionDigits })).format(n)
}

/**
 * „vor 3 Tagen“ / „3 days ago“ / „hace 3 días“ …
 * `inline`: für die Satzmitte ("gerade eben" statt "Gerade eben").
 */
export function formatRelative(iso: string | null, inline = false): string {
  if (!iso) return t('format.neverPlayed')
  const diffSec = (new Date(iso).getTime() - Date.now()) / 1000
  const steps: [Intl.RelativeTimeFormatUnit, number][] = [
    ['year', 31_536_000],
    ['month', 2_592_000],
    ['day', 86_400],
    ['hour', 3_600],
    ['minute', 60],
  ]
  const relative = cached('rel', (l) => new Intl.RelativeTimeFormat(l, { numeric: 'auto' }))
  for (const [unit, seconds] of steps) {
    if (Math.abs(diffSec) >= seconds) return relative.format(Math.round(diffSec / seconds), unit)
  }
  return inline ? t('format.justNowInline') : t('format.justNow')
}

function unit(value: number, unitName: 'kilobyte' | 'megabyte' | 'gigabyte', digits = 1): string {
  return cached(`u${unitName}${digits}`, (l) =>
    new Intl.NumberFormat(l, { style: 'unit', unit: unitName, unitDisplay: 'short', maximumFractionDigits: digits }),
  ).format(value)
}

export function formatMemory(mb: number): string {
  return mb >= 1024 ? unit(mb / 1024, 'gigabyte') : unit(mb, 'megabyte', 0)
}

export function formatPlayTime(seconds: number): string {
  if (seconds < 60) return seconds > 0 ? t('format.playTime.underMinute') : '–'
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  return hours > 0
    ? t('format.playTime.hoursMinutes', { hours: formatNumber(hours), minutes })
    : t('format.playTime.minutes', { minutes })
}

/** Name einer Start-Stufe („Bibliotheken“, „Libraries“ …). */
export function stageLabel(stage: LaunchStage): string {
  return t(`launchStage.${stage}`)
}

// Grobe Gewichtung der Stufen für EINE durchgehende Prozentanzeige.
const stageWeights: [LaunchStage, number][] = [
  ['version', 2],
  ['java', 24],
  ['loader', 14],
  ['libraries', 16],
  ['assets', 42],
  ['starting', 2],
]

export function overallPercent(stage: LaunchStage, stagePercent: number): number {
  let done = 0
  for (const [name, weight] of stageWeights) {
    if (name === stage) return Math.min(100, done + (weight * stagePercent) / 100)
    done += weight
  }
  return 100
}

/** Mehrzahl-Bezeichnung einer Inhaltsart („Mods“, „Ressourcenpakete“ …). */
export function contentKindLabel(kind: ContentKind): string {
  return t(`contentKind.${kind}`)
}

export const contentKinds: ContentKind[] = ['mod', 'resourcepack', 'shaderpack', 'datapack']

export function formatFileSize(bytes: number): string {
  if (bytes >= 1_048_576) return unit(bytes / 1_048_576, 'megabyte')
  return unit(Math.max(1, Math.round(bytes / 1024)), 'kilobyte', 0)
}

/** Größen bis in den GB-Bereich (Speicherverwaltung). */
export function formatBytes(bytes: number): string {
  if (bytes >= 1_073_741_824) return unit(bytes / 1_073_741_824, 'gigabyte')
  if (bytes >= 1_048_576) return unit(Math.round(bytes / 1_048_576), 'megabyte', 0)
  if (bytes <= 0) return unit(0, 'megabyte', 0)
  return unit(Math.max(1, Math.round(bytes / 1024)), 'kilobyte', 0)
}

/** Kompakte Zahl („1,2 Mio.“ / „1.2M“). */
export function formatCount(n: number): string {
  return cached('compact', (l) => new Intl.NumberFormat(l, { notation: 'compact', maximumFractionDigits: 1 })).format(n)
}

/** Bewährte G1-Einstellungen für den Client – weniger Ruckler durch kürzere GC-Pausen. */
export const optimizedJvmArgs =
  '-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=50 -XX:+UnlockExperimentalVMOptions ' +
  '-XX:+DisableExplicitGC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:G1HeapRegionSize=32M'

/** Datum mit Uhrzeit („23.09.2026, 14:05“ / „Sep 23, 2026, 2:05 PM“). */
export function formatDate(iso: string | null): string {
  return iso ? cached('dt', (l) => new Intl.DateTimeFormat(l, { dateStyle: 'medium', timeStyle: 'short' })).format(new Date(iso)) : ''
}

/** Nur das Datum, kurz („23.09.2026“ / „9/23/2026“); unbekannt → „–“. */
export function formatShortDate(iso: string | null | undefined): string {
  if (!iso) return '–'
  const ms = Date.parse(iso)
  return Number.isNaN(ms) ? '–' : cached('d', (l) => new Intl.DateTimeFormat(l, { year: 'numeric', month: '2-digit', day: '2-digit' })).format(new Date(ms))
}

/** Nur die Uhrzeit („14:05“ / „2:05 PM“). */
export function formatTime(iso: string | Date): string {
  return cached('t', (l) => new Intl.DateTimeFormat(l, { timeStyle: 'short' })).format(typeof iso === 'string' ? new Date(iso) : iso)
}

/** Aufzählung in der Sprache („A, B und C“ / „A, B, and C“). */
export function formatList(items: string[]): string {
  const locale = intlLocale()
  return new Intl.ListFormat(locale, { style: 'long', type: 'conjunction' }).format(items)
}

/** Vergleich für Sortierungen nach Namen in der eingestellten Sprache. */
export function compareText(a: string, b: string): number {
  return a.localeCompare(b, intlLocale(), { sensitivity: 'base' })
}

export const importSources: ImportSource[] = [
  'vanilla',
  'curseforge',
  'modrinth',
  'prism',
  'multimc',
  'lunar',
  'badlion',
  'feather',
  'oneclient',
  'atlauncher',
  'gdlaunchercarbon',
  'gdlauncher',
  'tlauncher',
  'folder',
]

/** Name der Quelle eines Imports (Launcher-Namen bleiben, „Eigener Ordner“ wird übersetzt). */
export function importSourceLabel(source: ImportSource): string {
  return t(`importSource.${source}`)
}

export const syncItemKeys: SyncItem[] = ['options', 'servers', 'resourcePacks', 'commandHistory', 'hotbar']

/** Was zwischen Instanzen synchronisiert werden kann – mit übersetzten Texten. */
export function syncItemList(): { key: SyncItem; label: string; description: string }[] {
  return syncItemKeys.map((key) => ({
    key,
    label: t(`syncItem.${key}.label`),
    description: t(`syncItem.${key}.description`),
  }))
}

/**
 * Welche Java-Hauptversion (8/17/21/25) eine Minecraft-Version grob braucht –
 * nur für Anzeige und Platzhalter; der Kern liest es genau aus dem Versions-JSON.
 */
export function javaMajorFor(gameVersion: string): 8 | 17 | 21 | 25 {
  const release = /^1\.(\d+)(?:\.(\d+))?/.exec(gameVersion)
  if (release) {
    const minor = Number(release[1])
    const patch = Number(release[2] ?? 0)
    if (minor > 20 || (minor === 20 && patch >= 5)) return 21
    return minor >= 17 ? 17 : 8
  }
  // Jahresversionen (26.1) und Snapshots (26w14a): ab 26 Java 25.
  const year = /^(\d{2})(?:\.|w)/.exec(gameVersion)
  if (year) return Number(year[1]) >= 26 ? 25 : 21
  return 21
}

/** Vanilla mit TRS-Optimierung läuft als Fabric – Suche und Filter richten sich danach. */
export function effectiveLoader(instance: Instance | null | undefined): LoaderKind | null {
  if (!instance) return null
  return instance.loader.kind === "vanilla" && instance.overrides.boost !== false ? "fabric" : instance.loader.kind
}
