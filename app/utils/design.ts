import { readonly, ref } from 'vue'
import type { Accent, Design } from '~/types'

// Design des Launchers: „Redstone“ (Standard) oder „Winternacht“. Steht als
// `data-design="winter"` auf <html> (Redstone ohne Attribut) – das CSS tauscht
// damit Farben und Muster, die Szenen-Komponenten den Hintergrund. Akzentfarbe
// und Farbschema bleiben eigene Einstellungen.

export const designs = ['redstone', 'winter'] as const satisfies readonly Design[]

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
 * Akzent beim Wechsel des Designs: Winternacht mit dem Redstone-Akzent bekommt
 * Eisblau, zurück zu Redstone wird aus Eisblau wieder Redstone. Jede andere
 * (selbst gewählte) Farbe bleibt.
 */
export function accentForDesign(design: Design, accent: Accent): Accent {
  if (design === 'winter' && accent === 'redstone') return 'ice'
  if (design === 'redstone' && accent === 'ice') return 'redstone'
  return accent
}

/** Aktuelles Design laut <html> (Szenen-Komponenten, Banner). */
export function documentDesign(): Design {
  if (typeof document === 'undefined') return 'redstone'
  return normalizeDesign(document.documentElement.dataset.design)
}
