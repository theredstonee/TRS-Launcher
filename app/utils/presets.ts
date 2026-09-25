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
  fpsShaderLite: 'presets.builtin.fpsShaderLite.name',
  fpsShader: 'presets.builtin.fpsShader.name',
  nvidium: 'presets.builtin.nvidium.name',
  voiceChat: 'presets.builtin.voiceChat.name',
  replay: 'presets.builtin.replay.name',
}

const builtinDescriptions: Record<BuiltinPreset, MessageKey> = {
  fpsBoost: 'presets.builtin.fpsBoost.description',
  fpsShaderLite: 'presets.builtin.fpsShaderLite.description',
  fpsShader: 'presets.builtin.fpsShader.description',
  nvidium: 'presets.builtin.nvidium.description',
  voiceChat: 'presets.builtin.voiceChat.description',
  replay: 'presets.builtin.replay.description',
}

// --- FPS-Boost-Stufen --------------------------------------------------------------

/** Die drei Stufen des FPS-Boosts – genau eine ist gewählt (oder keine). */
export const fpsTiers = ['fpsBoost', 'fpsShaderLite', 'fpsShader'] as const
export type FpsTier = (typeof fpsTiers)[number]

/** Kurzname und Satz je Stufe (für die Stufenwahl). */
export const fpsTierTexts: Record<FpsTier, { name: MessageKey; hint: MessageKey }> = {
  fpsBoost: { name: 'presets.tiers.fpsBoost.name', hint: 'presets.tiers.fpsBoost.hint' },
  fpsShaderLite: { name: 'presets.tiers.fpsShaderLite.name', hint: 'presets.tiers.fpsShaderLite.hint' },
  fpsShader: { name: 'presets.tiers.fpsShader.name', hint: 'presets.tiers.fpsShader.hint' },
}

export function isFpsTier(builtin: BuiltinPreset | null | undefined): builtin is FpsTier {
  return builtin === 'fpsBoost' || builtin === 'fpsShaderLite' || builtin === 'fpsShader'
}

/** Stufe mit Iris + Shaderpaket. */
export function isShaderTier(builtin: BuiltinPreset | null | undefined): boolean {
  return builtin === 'fpsShaderLite' || builtin === 'fpsShader'
}

/** Iris gibt es für Fabric, Quilt und NeoForge – nur dort sind Shader-Stufen wählbar. */
export function loaderHasShaders(loader: LoaderKind | null): boolean {
  return loader === null || loader === 'fabric' || loader === 'quilt' || loader === 'neoforge'
}

/** Preset-ID einer Stufe (falls es sie in der Liste gibt). */
export function tierPresetId(presets: Pick<Preset, 'id' | 'builtin'>[], tier: FpsTier): string | null {
  return presets.find((p) => p.builtin === tier)?.id ?? null
}

/** Welche Stufe gerade gewählt ist (`null` = FPS-Boost aus). */
export function selectedFpsTier(presets: Pick<Preset, 'id' | 'builtin'>[], selected: string[]): FpsTier | null {
  for (const id of selected) {
    const builtin = presets.find((p) => p.id === id)?.builtin
    if (isFpsTier(builtin)) return builtin
  }
  return null
}

/**
 * Auswahl mit genau dieser Stufe (`null` = FPS-Boost aus). Mit Shadern fällt
 * Nvidium heraus – es verträgt sich nicht mit Iris.
 */
export function withFpsTier(presets: Pick<Preset, 'id' | 'builtin'>[], selected: string[], tier: FpsTier | null): string[] {
  const builtinOf = (id: string) => presets.find((p) => p.id === id)?.builtin ?? null
  const next = selected.filter((id) => {
    const b = builtinOf(id)
    return !isFpsTier(b) && !(isShaderTier(tier) && b === 'nvidium')
  })
  const id = tier ? tierPresetId(presets, tier) : null
  return id ? [...next, id] : next
}

/** Nvidium ist mit einer Shader-Stufe nicht kombinierbar. */
export function presetBlocked(preset: Pick<Preset, 'builtin'>, presets: Pick<Preset, 'id' | 'builtin'>[], selected: string[]): boolean {
  return preset.builtin === 'nvidium' && isShaderTier(selectedFpsTier(presets, selected))
}

/** Eine Zeile im Auswahlfeld: ein Preset oder der FPS-Boost mit seinen Stufen. */
export type PresetRow = { type: 'preset'; preset: Preset } | { type: 'fps'; tiers: Preset[] }

/** Fasst die Stufen zu einer Zeile zusammen – an der Stelle der ersten. */
export function presetRows(presets: Preset[]): PresetRow[] {
  const tiers = presets.filter((p) => isFpsTier(p.builtin))
  const rows: PresetRow[] = []
  for (const p of presets) {
    if (!isFpsTier(p.builtin)) rows.push({ type: 'preset', preset: p })
    else if (p === tiers[0]) rows.push({ type: 'fps', tiers })
  }
  return rows
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
 * dort ist der FPS-Boost (alle Stufen) nicht wählbar. Shader-Stufen brauchen
 * einen Loader mit Iris.
 */
export function presetDisabled(preset: Pick<Preset, 'builtin'>, loader: LoaderKind | null): boolean {
  if (loader === 'vanilla' && isFpsTier(preset.builtin)) return true
  return isShaderTier(preset.builtin) && !loaderHasShaders(loader)
}

/** Was ein Preset-Auswahlfeld anzeigen soll (Nvidium nur mit passender Karte). */
export function visiblePresets(presets: Preset[], context: 'instance' | 'modpack'): Preset[] {
  return presets.filter((p) => p.available && (context === 'instance' || p.modpackSafe))
}

/**
 * Auswahl an einen neuen Loader anpassen: Unpassendes fällt weg; eine
 * Shader-Stufe ohne Iris (Forge) wird zu „Max FPS“ statt ganz zu verschwinden.
 */
export function adjustSelectionForLoader(presets: Preset[], selected: string[], loader: LoaderKind | null): string[] {
  const tier = selectedFpsTier(presets, selected)
  const disabled = new Set(presets.filter((p) => presetDisabled(p, loader)).map((p) => p.id))
  const next = selected.filter((id) => !disabled.has(id))
  const maxFps = presets.find((p) => p.builtin === 'fpsBoost')
  if (tier && tier !== 'fpsBoost' && selectedFpsTier(presets, next) === null && maxFps && !presetDisabled(maxFps, loader)) {
    return [...next, maxFps.id]
  }
  return next.length === selected.length ? selected : next
}

/** Vorausgewählt: alles mit „immer automatisch“, das hier passt (höchstens eine FPS-Stufe). */
export function defaultPresetSelection(
  presets: Preset[],
  options: { loader: LoaderKind | null; context: 'instance' | 'modpack' },
): string[] {
  const visible = visiblePresets(presets, options.context)
  const auto = visible.filter((p) => p.auto)
  const tier = selectedFpsTier(visible, auto.map((p) => p.id))
  const selection = auto
    .filter((p) => (isFpsTier(p.builtin) ? p.builtin === tier : !(p.builtin === 'nvidium' && isShaderTier(tier))))
    .map((p) => p.id)
  return adjustSelectionForLoader(visible, selection, options.loader)
}

const okStatuses: PresetItemStatus[] = ['installed', 'alreadyInstalled', 'duplicate', 'swapped', 'bundled']

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
    case 'swapped':
      return t('presets.reason.swapped', { other: item.compatWith ?? '?' })
    case 'bundled':
      // Kommt schon mit dem TRS Client (eingebaute Optimierungen) – kein eigener Download.
      return t('presets.reason.bundled')
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
