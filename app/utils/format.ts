import type { ContentKind, ImportSource, LaunchStage, LoaderKind } from '~/types'

export const loaderLabels: Record<LoaderKind, string> = {
  vanilla: 'Vanilla',
  fabric: 'Fabric',
  quilt: 'Quilt',
  forge: 'Forge',
  neoforge: 'NeoForge',
}

const relative = new Intl.RelativeTimeFormat('de', { numeric: 'auto' })

export function formatRelative(iso: string | null): string {
  if (!iso) return 'Noch nie gespielt'
  const diffSec = (new Date(iso).getTime() - Date.now()) / 1000
  const steps: [Intl.RelativeTimeFormatUnit, number][] = [
    ['year', 31_536_000],
    ['month', 2_592_000],
    ['day', 86_400],
    ['hour', 3_600],
    ['minute', 60],
  ]
  for (const [unit, seconds] of steps) {
    if (Math.abs(diffSec) >= seconds) return relative.format(Math.round(diffSec / seconds), unit)
  }
  return 'Gerade eben'
}

export function formatMemory(mb: number): string {
  return mb >= 1024 ? `${(mb / 1024).toLocaleString('de', { maximumFractionDigits: 1 })} GB` : `${mb} MB`
}

export function formatPlayTime(seconds: number): string {
  if (seconds < 60) return seconds > 0 ? 'unter 1 Min.' : '–'
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  return hours > 0 ? `${hours} Std. ${minutes} Min.` : `${minutes} Min.`
}

export const stageLabels: Record<LaunchStage, string> = {
  version: 'Versionsdaten',
  java: 'Java',
  loader: 'Modloader',
  libraries: 'Bibliotheken',
  assets: 'Spieldateien',
  starting: 'Starte Spiel',
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

export const contentKindLabels: Record<ContentKind, string> = {
  mod: 'Mods',
  resourcepack: 'Ressourcenpakete',
  shaderpack: 'Shader',
}

export const contentKinds: ContentKind[] = ['mod', 'resourcepack', 'shaderpack']

export function formatFileSize(bytes: number): string {
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toLocaleString('de', { maximumFractionDigits: 1 })} MB`
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
}

export function formatCount(n: number): string {
  return new Intl.NumberFormat('de', { notation: 'compact', maximumFractionDigits: 1 }).format(n)
}

/** Bewährte G1-Einstellungen für den Client – weniger Ruckler durch kürzere GC-Pausen. */
export const optimizedJvmArgs =
  '-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=50 -XX:+UnlockExperimentalVMOptions ' +
  '-XX:+DisableExplicitGC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:G1HeapRegionSize=32M'

export function formatDate(iso: string | null): string {
  return iso ? new Intl.DateTimeFormat('de', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso)) : ''
}

export const importSourceLabels: Record<ImportSource, string> = {
  vanilla: 'Minecraft Launcher',
  prism: 'Prism Launcher',
  multimc: 'MultiMC',
  curseforge: 'CurseForge',
}
