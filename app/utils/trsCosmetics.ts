// Texte rund um Kosmetik-Optionen (Bart an/aus …) und Begleiter am Boden (Pinguin) – Anhang D
// des Kosmetik-Formats. Reine Funktionen, damit Skins-Seite und Tests dieselbe Logik nutzen.

import { hasKey, i18n, tKey } from './i18n'
import { modelOptions, optionName, petCount, resolveOptions, type CosmeticOption, type OptionChoices } from './cosmetic-v2/format'
import type { TrsHeadCosmetic } from './trs'

/**
 * „2 Pinguine – Creator-Stufe“: Anzahl des getragenen Begleiters am Boden. Für bekannte Teile
 * (Pinguin) mit eigener Mehrzahl, sonst „2 × Name“. Fehlt die Anzahl (ältere API), gilt 1.
 */
export function petCountText(item: Pick<TrsHeadCosmetic, 'id' | 'name' | 'count' | 'creator'>): string {
  const n = petCount(item.count ?? 1)
  const key = `headCosmetics.pet.counts.${item.id}`
  const count = hasKey(key) ? tKey(key, n) : tKey('headCosmetics.pet.count', { name: item.name }, n)
  return item.creator ? tKey('headCosmetics.pet.creatorTier', { label: count }) : count
}

/** Eine Option mit Anzeigename (Sprache des Launchers) und aktuellem Wert. */
export interface OptionToggle {
  option: CosmeticOption
  label: string
  on: boolean
}

/** Schalter für die Optionen eines Modells (leer, wenn es keine deklariert). */
export function optionToggles(model: unknown, choices: OptionChoices | null | undefined, locale = String(i18n.global.locale.value)): OptionToggle[] {
  const values = resolveOptions(model, choices)
  return modelOptions(model).map((option) => ({ option, label: optionName(option, locale), on: values[option.id] ?? option.default }))
}

/**
 * Wahl nach dem Umschalten einer Option: alle Optionen des Modells mit Wert (so weiß der Server
 * genau, was gilt – auch wenn ein Standardwert später geändert wird).
 */
export function toggleOption(model: unknown, choices: OptionChoices | null | undefined, id: string): OptionChoices {
  const values = resolveOptions(model, choices)
  if (!(id in values)) return values
  return { ...values, [id]: !values[id] }
}
