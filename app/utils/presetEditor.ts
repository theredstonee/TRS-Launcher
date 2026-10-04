import type {
  ContentKind,
  ModrinthHit,
  Platform,
  PresetCheck,
  PresetConflict,
  PresetDepRef,
  PresetItem,
  PresetPickItem,
  PresetSource,
} from '~/types'
import { t } from './i18n'
import { icons, type IconName } from './icons'
import { PRESET_COLORS, PRESET_ICONS, PRESET_ITEMS_MAX, isPresetIconUrl, isPresetProjectId, type PresetColorName } from './schemas'

// Hilfen für den Preset-Editor: Aussehen (Symbol + Farbe), Einträge aus der Suche
// bzw. aus „Aus Modpack übernehmen“, Abhängigkeiten und Konflikte je Eintrag.

/** Farben der Palette (feste Werte, damit ein Preset in jedem Design gleich aussieht). */
export const presetColorHex: Record<PresetColorName, string> = {
  redstone: '#e0281e',
  amber: '#f59e0b',
  lime: '#84cc16',
  emerald: '#10b981',
  cyan: '#06b6d4',
  sky: '#3b82f6',
  violet: '#8b5cf6',
  pink: '#ec4899',
  slate: '#64748b',
}

export function presetColor(color: string | null | undefined): string {
  return (PRESET_COLORS as readonly string[]).includes(color ?? '') ? presetColorHex[color as PresetColorName] : presetColorHex.redstone
}

/** Pfad des Symbols – auch die festen Symbole der TRS-Presets (z. B. `chat`). */
export function presetIconPath(icon: string | null | undefined): string {
  return icon && icon in icons ? icons[icon as IconName] : icons.presets
}

export const presetIconChoices = PRESET_ICONS
export const presetColorChoices = PRESET_COLORS

/** Arten, die der Editor anbietet (Datenpakete gehören in Welten, nicht in Presets). */
export const presetKinds = ['mod', 'resourcepack', 'shaderpack'] as const satisfies readonly ContentKind[]

export function presetItemKey(item: { source: PresetSource; projectId: string }): string {
  return `${item.source}:${item.projectId}`
}

/** Vereinfachter Name zum Vergleich über Quellen hinweg („Xaero's Minimap“ → „xaerosminimap“). */
export function simpleName(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]/g, '')
}

/** Ein Treffer der Suche als Preset-Eintrag. */
export function hitToItem(hit: ModrinthHit, platform: Platform, kind: ContentKind): PresetItem {
  return {
    source: platform,
    projectId: hit.projectId,
    title: (hit.title.trim() || hit.slug).slice(0, 100),
    iconUrl: hit.iconUrl && isPresetIconUrl(hit.iconUrl) ? hit.iconUrl : null,
    kind,
  }
}

/** Gruppiert nach Art in fester Reihenfolge (Mods, Ressourcenpakete, Shader, Rest). */
export function groupPresetItems(items: PresetItem[]): { kind: ContentKind; items: PresetItem[] }[] {
  const order: ContentKind[] = ['mod', 'resourcepack', 'shaderpack', 'datapack']
  return order.map((kind) => ({ kind, items: items.filter((i) => i.kind === kind) })).filter((g) => g.items.length)
}

// --- Aus Modpack übernehmen ------------------------------------------------------

/** Lässt sich ins Preset übernehmen (Quelle + gültige Projekt-ID)? */
export function isPickable(item: PresetPickItem): item is PresetPickItem & { source: PresetSource; projectId: string } {
  return !!item.source && !!item.projectId && isPresetProjectId(item.source, item.projectId)
}

export function pickKey(item: PresetPickItem): string {
  return isPickable(item) ? presetItemKey(item) : `file:${item.kind}:${item.fileName ?? item.title}`
}

export interface PickFilter {
  query: string
  kind: ContentKind | 'all'
  /** Nur Optimierungs-Mods. */
  performance: boolean
  /** Nur, was ausschließlich im Client läuft. */
  clientOnly: boolean
}

export const emptyPickFilter = (): PickFilter => ({ query: '', kind: 'all', performance: false, clientOnly: false })

export function filterPicks(items: PresetPickItem[], filter: PickFilter): PresetPickItem[] {
  const q = filter.query.trim().toLowerCase()
  return items.filter(
    (i) =>
      (filter.kind === 'all' || i.kind === filter.kind) &&
      (!filter.performance || i.performance) &&
      (!filter.clientOnly || i.clientOnly === true) &&
      (!q || i.title.toLowerCase().includes(q) || (i.fileName ?? '').toLowerCase().includes(q)),
  )
}

/** Steckt das Projekt schon im Preset (gleiche ID oder gleicher Name bei gleicher Art)? */
export function inPreset(existing: PresetItem[], item: PresetPickItem): boolean {
  if (!isPickable(item)) return false
  const key = presetItemKey(item)
  const name = simpleName(item.title)
  return existing.some((e) => presetItemKey(e) === key || (!!name && e.kind === item.kind && simpleName(e.title) === name))
}

export function pickToItem(item: PresetPickItem): PresetItem | null {
  if (!isPickable(item)) return null
  return {
    source: item.source,
    projectId: item.projectId,
    title: (item.title.trim() || item.projectId).slice(0, 100),
    iconUrl: item.iconUrl && isPresetIconUrl(item.iconUrl) ? item.iconUrl : null,
    kind: item.kind,
  }
}

export interface MergeResult {
  items: PresetItem[]
  added: number
  /** Schon drin (gleiches Projekt) – übersprungen. */
  duplicates: number
  /** Kein Platz mehr (höchstens `PRESET_ITEMS_MAX`). */
  overflow: number
}

/** Übernimmt die gewählten Einträge: Doppeltes und Nicht-Übernehmbares fällt weg. */
export function mergePicks(existing: PresetItem[], picked: PresetPickItem[], max = PRESET_ITEMS_MAX): MergeResult {
  const items = [...existing]
  let added = 0
  let duplicates = 0
  let overflow = 0
  for (const pick of picked) {
    const item = pickToItem(pick)
    if (!item) continue
    if (inPreset(items, pick)) {
      duplicates++
      continue
    }
    if (items.length >= max) {
      overflow++
      continue
    }
    items.push(item)
    added++
  }
  return { items, added, duplicates, overflow }
}

// --- Abhängigkeiten + Konflikte --------------------------------------------------

/** Pflicht-Abhängigkeiten eines Eintrags, ohne die, die schon im Preset stehen. */
export function depsOf(check: PresetCheck | null, item: PresetItem, items: PresetItem[]): PresetDepRef[] {
  const entry = check?.deps.find((d) => d.source === item.source && d.projectId === item.projectId)
  if (!entry) return []
  const have = new Set(items.map(presetItemKey))
  const names = new Set(items.map((i) => simpleName(i.title)))
  return entry.deps.filter((d) => !have.has(presetItemKey(d)) && !names.has(simpleName(d.title)))
}

export function conflictsOf(check: PresetCheck | null, item: PresetItem): PresetConflict[] {
  const key = presetItemKey(item)
  return (check?.conflicts ?? []).filter((c) => presetItemKey(c.a) === key || (c.b && presetItemKey(c.b) === key))
}

export function conflictText(c: PresetConflict): string {
  if (c.reason === 'trsClient' || !c.b) return t('presets.editor.conflict.trsClient', { a: c.a.title })
  return t(`presets.editor.conflict.${c.reason}`, { a: c.a.title, b: c.b.title })
}
