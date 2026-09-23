// Reine Helfer für das Aufgaben-Panel – getestet in tests/tasks.test.ts.
// Keine Nuxt-Auto-Imports, damit die Tests sie direkt laden können.
import type { PackProgress, TaskKind } from '../types'

/** Beschriftung im Verlauf („vor 2 Monaten · Modpack“). */
export const taskKindLabels: Record<TaskKind, string> = {
  modpack: 'Modpack',
  'modpack-file': 'Modpack aus Datei',
  content: 'Inhalt installiert',
  'content-update': 'Inhalte aktualisiert',
  'performance-pack': 'Leistungspaket',
  java: 'Java installiert',
  import: 'Instanz importiert',
  export: 'Modpack exportiert',
  create: 'Neue Instanz',
  duplicate: 'Instanz dupliziert',
  repair: 'Instanz repariert',
  reinstall: 'Neu installiert',
  'version-change': 'Version gewechselt',
  launch: 'Spielstart',
}

const units = ['B', 'KB', 'MB', 'GB', 'TB']

/** „20,6 MB“, „9,79 MB“, „172 MB“ – drei gültige Stellen. */
export function formatSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  let value = bytes
  let unit = 0
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  const digits = unit === 0 ? 0 : value >= 100 ? 0 : value >= 10 ? 1 : 2
  return `${value.toLocaleString('de', { minimumFractionDigits: digits, maximumFractionDigits: digits })} ${units[unit]}`
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
    return v.toLocaleString('de', { minimumFractionDigits: digits, maximumFractionDigits: digits })
  }
  return `${fmt(Math.min(done, total))} / ${fmt(total)} ${units[unit]}`
}

/** Restzeit: „8 s“, „3 min“, „1 h 5 min“; `null`, wenn unbekannt. */
export function formatEta(remainingBytes: number, bytesPerSecond: number): string | null {
  if (remainingBytes <= 0 || bytesPerSecond <= 0) return null
  const seconds = Math.ceil(remainingBytes / bytesPerSecond)
  if (seconds > 24 * 3600) return null
  if (seconds < 60) return `${seconds} s`
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest ? `${hours} h ${rest} min` : `${hours} h`
}

const relative = new Intl.RelativeTimeFormat('de', { numeric: 'auto' })

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
    if (Math.abs(diff) >= seconds) return relative.format(Math.trunc(diff / seconds), unit)
  }
  return 'gerade eben'
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

export const packStageLabels: Record<PackProgress['phase'], string> = {
  pack: 'Modpack wird geladen',
  files: 'Mods werden geladen',
  overrides: 'Dateien werden entpackt',
}

/** Aufgaben-IDs gehen an den Kern – nur harmlose Zeichen, begrenzte Länge. */
export function taskKey(...parts: string[]): string {
  return parts
    .map((p) => p.replace(/[^A-Za-z0-9._-]/g, '_'))
    .join(':')
    .slice(0, 128)
}
