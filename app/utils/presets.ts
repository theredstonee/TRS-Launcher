// Reine Helfer rund um Mod-Presets – getestet in tests/presets.test.ts.
// Keine Nuxt-Auto-Imports, damit die Tests sie direkt laden können.
import type {
  BuiltinPreset,
  LoaderKind,
  Preset,
  PresetApplyReport,
  PresetItemOutcome,
  PresetItemStatus,
  PresetProgress,
} from '../types'
import { loaderLabels } from './format'
import { t, type MessageKey } from './i18n'

const builtinNames: Record<BuiltinPreset, MessageKey> = {
  fpsBoost: 'presets.builtin.fpsBoost.name',
  nvidium: 'presets.builtin.nvidium.name',
  voiceChat: 'presets.builtin.voiceChat.name',
  replay: 'presets.builtin.replay.name',
}

const builtinDescriptions: Record<BuiltinPreset, MessageKey> = {
  fpsBoost: 'presets.builtin.fpsBoost.description',
  nvidium: 'presets.builtin.nvidium.description',
  voiceChat: 'presets.builtin.voiceChat.description',
  replay: 'presets.builtin.replay.description',
}

/** Anzeigename – fertige Presets in der eingestellten Sprache. */
export function presetName(preset: Pick<Preset, 'name' | 'builtin'>): string {
  return preset.builtin ? t(builtinNames[preset.builtin]) : preset.name
}

/** Kurze Beschreibung fertiger Presets; eigene haben keine. */
export function presetDescription(preset: Pick<Preset, 'builtin'>): string | null {
  return preset.builtin ? t(builtinDescriptions[preset.builtin]) : null
}

/** Enthält das Preset Mods (die einen Modloader brauchen)? */
export function presetHasMods(preset: Pick<Preset, 'builtin' | 'items'>): boolean {
  return preset.builtin !== null || preset.items.some((i) => i.kind === 'mod')
}

/**
 * Vanilla-Instanzen bekommen die Performance-Mods über die TRS-Optimierung –
 * dort ist FPS-Boost deshalb nicht wählbar.
 */
export function presetDisabled(preset: Pick<Preset, 'builtin'>, loader: LoaderKind | null): boolean {
  return loader === 'vanilla' && preset.builtin === 'fpsBoost'
}

/** Was ein Preset-Auswahlfeld anzeigen soll (Nvidium nur mit passender Karte). */
export function visiblePresets(presets: Preset[], context: 'instance' | 'modpack'): Preset[] {
  return presets.filter((p) => p.available && (context === 'instance' || p.modpackSafe))
}

/** Vorausgewählt: alles mit „immer automatisch“, das hier passt. */
export function defaultPresetSelection(
  presets: Preset[],
  options: { loader: LoaderKind | null; context: 'instance' | 'modpack' },
): string[] {
  return visiblePresets(presets, options.context)
    .filter((p) => p.auto && !presetDisabled(p, options.loader))
    .map((p) => p.id)
}

const okStatuses: PresetItemStatus[] = ['installed', 'alreadyInstalled', 'duplicate']

export function presetItemOk(item: Pick<PresetItemOutcome, 'status'>): boolean {
  return okStatuses.includes(item.status)
}

/**
 * Zusammenfassung eines Berichts. Teile einer Sammlung (FPS-Boost), die es
 * für die Version einfach nicht gibt, zählen nicht mit – sie landen in `quiet`.
 */
export function summarizePresetReport(report: Pick<PresetApplyReport, 'items'>) {
  const quiet = report.items.filter((i) => i.optional && i.status === 'notAvailable')
  const counted = report.items.filter((i) => !quiet.includes(i))
  const problems = counted.filter((i) => !presetItemOk(i))
  return { done: counted.length - problems.length, total: counted.length, problems, quiet }
}

/** Warum ein Eintrag nicht installiert wurde – ein kurzer Satz. */
export function presetReason(item: PresetItemOutcome, report: Pick<PresetApplyReport, 'gameVersion' | 'loader'>): string {
  const title = item.title
  switch (item.status) {
    case 'installed':
      return t('presets.reason.installed')
    case 'alreadyInstalled':
      return t('presets.reason.alreadyInstalled')
    case 'duplicate':
      return t('presets.reason.duplicate')
    case 'notAvailable':
      // Bei Mods zählt auch der Loader („für 1.12.2 (Forge)“).
      return item.kind === 'mod' && report.loader !== 'vanilla'
        ? t('presets.reason.notAvailableLoader', { title, version: report.gameVersion, loader: loaderLabels[report.loader] })
        : t('presets.reason.notAvailable', { title, version: report.gameVersion })
    case 'missingDependency':
      return t('presets.reason.missingDependency', { title, dependency: item.detail ?? '?' })
    case 'incompatible':
      return t('presets.reason.incompatible', { title, other: item.detail ?? '?' })
    case 'needsLoader':
      return t('presets.reason.needsLoader', { title })
    case 'failed':
      return t('presets.reason.failed', { title })
  }
}

/** „3 von 4 installiert – Flashback gibt es für 1.12.2 nicht“. */
export function presetSummaryText(report: PresetApplyReport): string {
  const { done, total, problems } = summarizePresetReport(report)
  if (total === 0) return t('presets.report.nothing')
  if (!problems.length) return t('presets.report.allDone', done)
  const first = presetReason(problems[0]!, report)
  const more = problems.length > 1 ? ` ${t('presets.report.more', problems.length - 1)}` : ''
  return `${t('presets.report.partial', { done, total })} – ${first}${more}`
}

/** Fortschritt in Prozent: Auflösen 0–20 %, Laden 20–100 %. */
export function presetPercent(p: PresetProgress): number {
  const share = p.total > 0 ? Math.min(1, p.done / p.total) : 0
  return p.phase === 'resolve' ? Math.round(share * 20) : Math.round(20 + share * 80)
}

/** Stufentext der Aufgabe. */
export function presetStage(p: PresetProgress): string {
  if (p.phase === 'resolve') return t('presets.task.resolving', { done: p.done, total: p.total })
  return p.title
    ? t('presets.task.installing', { title: p.title, done: Math.min(p.done + 1, p.total), total: p.total })
    : t('presets.task.finishing')
}

/**
 * Neue Reihenfolge, wenn `id` um `delta` Plätze verschoben wird. Gezählt wird in
 * `visible` (ausgeblendete Presets wie Nvidium ohne passende Karte bleiben stehen).
 */
export function movePreset(ids: string[], id: string, delta: number, visible: string[] = ids): string[] {
  const target = visible[visible.indexOf(id) + delta]
  const from = ids.indexOf(id)
  const to = target === undefined ? -1 : ids.indexOf(target)
  if (!visible.includes(id) || from < 0 || to < 0 || target === undefined) return ids
  const next = [...ids]
  next[from] = target
  next[to] = id
  return next
}
