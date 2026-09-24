// Reine Helfer für das Aufgaben-Panel – getestet in tests/tasks.test.ts.
// Keine Nuxt-Auto-Imports, damit die Tests sie direkt laden können.
import type { PackProgress, TaskKind } from '../types'
import { intlLocale, t, type MessageKey } from './i18n'

const taskKindKeys: Record<TaskKind, MessageKey> = {
  modpack: 'tasks.kind.modpack',
  'modpack-file': 'tasks.kind.modpackFile',
  content: 'tasks.kind.content',
  'content-update': 'tasks.kind.contentUpdate',
  'performance-pack': 'tasks.kind.performancePack',
  java: 'tasks.kind.java',
  import: 'tasks.kind.import',
  export: 'tasks.kind.export',
  create: 'tasks.kind.create',
  duplicate: 'tasks.kind.duplicate',
  repair: 'tasks.kind.repair',
  reinstall: 'tasks.kind.reinstall',
  'version-change': 'tasks.kind.versionChange',
  launch: 'tasks.kind.launch',
  ffmpeg: 'tasks.kind.ffmpeg',
}

/** Beschriftung im Verlauf („vor 2 Monaten · Modpack“) in der eingestellten Sprache. */
export function taskKindLabel(kind: TaskKind): string {
  const key = taskKindKeys[kind]
  return key ? t(key) : kind
}

function decimal(value: number, digits: number): string {
  return value.toLocaleString(intlLocale(), { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

const units = ['B', 'KB', 'MB', 'GB', 'TB']

/** „20,6 MB“, „9,79 MB“, „172 MB“ – drei gültige Stellen, Dezimalzeichen der Sprache. */
export function formatSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  let value = bytes
  let unit = 0
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  const digits = unit === 0 ? 0 : value >= 100 ? 0 : value >= 10 ? 1 : 2
  return `${decimal(value, digits)} ${units[unit]}`
}

export function formatSpeed(bytesPerSecond: number): string {
  return `${formatSize(bytesPerSecond)}/s`
}

/** „17,9 / 172 MB“ – beide Werte in der Einheit der Gesamtgröße. */
export function formatProgressBytes(done: number, total: number): string {
  if (total <= 0) return formatSize(done)
  let unit = 0
  let scale = 1
  while (total / scale >= 1000 && unit < units.length - 1) {
    scale *= 1024
    unit++
  }
  const fmt = (n: number) => {
    const v = n / scale
    const digits = unit === 0 ? 0 : v >= 100 ? 0 : v >= 10 ? 1 : v > 0 ? 2 : 0
    return decimal(v, digits)
  }
  return `${fmt(Math.min(done, total))} / ${fmt(total)} ${units[unit]}`
}

/** Restzeit: „8 s“, „3 min“, „1 h 5 min“; `null`, wenn unbekannt. */
export function formatEta(remainingBytes: number, bytesPerSecond: number): string | null {
  if (remainingBytes <= 0 || bytesPerSecond <= 0) return null
  const seconds = Math.ceil(remainingBytes / bytesPerSecond)
  if (seconds > 24 * 3600) return null
  if (seconds < 60) return t('tasks.eta.seconds', { seconds })
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return t('tasks.eta.minutes', { minutes })
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest ? t('tasks.eta.hoursMinutes', { hours, minutes: rest }) : t('tasks.eta.hours', { hours })
}

const relativeFormats = new Map<string, Intl.RelativeTimeFormat>()

function relativeFormat(): Intl.RelativeTimeFormat {
  const locale = intlLocale()
  let format = relativeFormats.get(locale)
  if (!format) {
    format = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' })
    relativeFormats.set(locale, format)
  }
  return format
}

/** „vor 1 Woche“, „vor 2 Monaten“, „gerade eben“. */
export function formatAgo(at: number, now = Date.now()): string {
  const diff = Math.round((at - now) / 1000)
  const steps: [Intl.RelativeTimeFormatUnit, number][] = [
    ['year', 31_536_000],
    ['month', 2_592_000],
    ['week', 604_800],
    ['day', 86_400],
    ['hour', 3_600],
    ['minute', 60],
  ]
  for (const [unit, seconds] of steps) {
    if (Math.abs(diff) >= seconds) return relativeFormat().format(Math.trunc(diff / seconds), unit)
  }
  return t('tasks.justNow')
}

/**
 * Misst die Geschwindigkeit über ein gleitendes Fenster. Sinkt der Stand
 * (Fehlversuch zurückgenommen), fängt die Messung neu an.
 */
export class SpeedMeter {
  private samples: { at: number; bytes: number }[] = []

  constructor(private readonly windowMs = 3000) {}

  /** Neuer Stand; liefert die aktuelle Geschwindigkeit in Bytes/s. */
  add(bytes: number, at: number): number {
    const last = this.samples.at(-1)
    if (last && bytes < last.bytes) this.samples = []
    this.samples.push({ at, bytes })
    while (this.samples.length > 2 && at - this.samples[0]!.at > this.windowMs) this.samples.shift()
    return this.speed()
  }

  speed(): number {
    const first = this.samples[0]
    const last = this.samples.at(-1)
    if (!first || !last || last.at <= first.at) return 0
    return Math.max(0, ((last.bytes - first.bytes) * 1000) / (last.at - first.at))
  }

  reset() {
    this.samples = []
  }
}

/** Modpack-Fortschritt als ein Prozentwert: Pack 0–10, Dateien 10–95, Overrides 95–100. */
export function packPercent(p: PackProgress): number {
  const base = { pack: 0, files: 10, overrides: 95 }[p.phase]
  const span = { pack: 10, files: 85, overrides: 5 }[p.phase]
  return Math.min(100, Math.floor(base + (p.percent / 100) * span))
}

const packStageKeys: Record<PackProgress['phase'], MessageKey> = {
  pack: 'tasks.stage.packLoading',
  files: 'tasks.stage.packFiles',
  overrides: 'tasks.stage.packOverrides',
}

/** Was beim Modpack-Installieren gerade passiert („Mods werden geladen“). */
export function packStageLabel(phase: PackProgress['phase']): string {
  return t(packStageKeys[phase])
}

/** Wie `packStageLabel`, als Objekt – übersetzt beim Lesen. */
export const packStageLabels: Readonly<Record<PackProgress['phase'], string>> = {
  get pack() {
    return packStageLabel('pack')
  },
  get files() {
    return packStageLabel('files')
  },
  get overrides() {
    return packStageLabel('overrides')
  },
}

/** Aufgaben-IDs gehen an den Kern – nur harmlose Zeichen, begrenzte Länge. */
export function taskKey(...parts: string[]): string {
  return parts
    .map((p) => p.replace(/[^A-Za-z0-9._-]/g, '_'))
    .join(':')
    .slice(0, 128)
}
