import { readonly, ref } from 'vue'
import type { Accent, Design } from '~/types'

// Design des Launchers: „Redstone“ (Standard), „Winternacht“ oder „Fuchsbau“. Steht als
// `data-design="winter"` bzw. `"fox"` auf <html> (Redstone ohne Attribut) – das CSS tauscht
// damit Farben und Muster, die Szenen-Komponenten den Hintergrund. Akzentfarbe
// und Farbschema bleiben eigene Einstellungen.

export const designs = ['redstone', 'winter', 'fox'] as const satisfies readonly Design[]

/** Akzent, den jedes Design ab Werk mitbringt. */
export const DESIGN_ACCENTS: Record<Design, Accent> = { redstone: 'redstone', winter: 'ice', fox: 'fox' }

/** Merker für den Splash, der vor Vue läuft (nächster Start). */
export const DESIGN_STORAGE_KEY = 'trs-design'

/** Unbekannte oder fehlende Werte (ältere Kerne) → Redstone. */
export function normalizeDesign(value: unknown): Design {
  return designs.includes(value as Design) ? (value as Design) : 'redstone'
}

/** Merker vom letzten Lauf – bis die Einstellungen da sind, flackert so nichts. */
function storedDesign(): Design {
  try {
    return typeof localStorage === 'undefined' ? 'redstone' : normalizeDesign(localStorage.getItem(DESIGN_STORAGE_KEY))
  } catch {
    return 'redstone'
  }
}

/** Gerade angezeigtes Design (folgt auch der Vorschau im Einstellungsfenster). */
const active = ref<Design>(storedDesign())

/** Reaktives Design für Komponenten (Hintergrund-Szene, Banner, Knöpfe). */
export function useDesign() {
  return readonly(active)
}

type DesignRoot ={ dataset: Record<string, string | undefined> }
type DesignStorage = { setItem(key: string, value: string): void; removeItem(key: string): void }

/** Design anwenden; `target` nur für Tests, sonst Dokument und localStorage. */
export function applyDesign(value: unknown, target?: { root: DesignRoot | null; storage: DesignStorage | null }): Design {
  const design = normalizeDesign(value)
  const root = target ? target.root : typeof document === 'undefined' ? null : document.documentElement
  const storage = target ? target.storage : typeof localStorage === 'undefined' ? null : localStorage
  if (root) {
    if (design === 'redstone') delete root.dataset.design
    else root.dataset.design = design
  }
  if (!target) active.value = design
  if (storage) {
    try {
      if (design === 'redstone') storage.removeItem(DESIGN_STORAGE_KEY)
      else storage.setItem(DESIGN_STORAGE_KEY, design)
    } catch {
      // Privater Modus: der Splash bleibt dann beim Redstone-Bild.
    }
  }
  return design
}

/**
 * Akzent beim Wechsel des Designs: ist noch der Akzent eines Designs gewählt
 * (Redstone, Eisblau, Fuchsorange), kommt der des neuen Designs – Winternacht mit
 * Redstone wird Eisblau, Fuchsbau Fuchsorange, zurück zu Redstone wieder Redstone.
 * Jede andere (selbst gewählte) Farbe bleibt.
 */
export function accentForDesign(design: Design, accent: Accent): Accent {
  return Object.values(DESIGN_ACCENTS).includes(accent) ? DESIGN_ACCENTS[design] : accent
}

/** Aktuelles Design laut <html> (Szenen-Komponenten, Banner). */
export function documentDesign(): Design {
  if (typeof document === 'undefined') return 'redstone'
  return normalizeDesign(document.documentElement.dataset.design)
}
