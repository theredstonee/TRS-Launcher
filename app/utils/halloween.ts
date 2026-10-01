// Halloween 2026 (Vertrag): nur solange `me.events` das Event enthält.
// Die gespeicherte Akzentfarbe (`data-accent`) bleibt unangetastet – das CSS
// überlagert die Farben, solange `data-event="halloween"` auf <html> steht.

export const HALLOWEEN_EVENT = 'halloween'
/** Merker für den Splash, der vor Vue läuft (nächster Start). */
export const EVENT_STORAGE_KEY = 'trs-event'
export const HALLOWEEN_WITCH = '/halloween/witch-final.png'
export const HALLOWEEN_BACKGROUND = '/halloween/background-final.png'

/** Hexe: 6 Frames à 48×48, 110 ms. */
export const WITCH_FRAME_MS = 110
export const WITCH_FRAMES = 6
/** Wippen ±2 px auf dem 2-px-Raster, Periode ~1,9 s – wie `halloween.js`. */
export const WITCH_BOB_MS = 300

type ThemeRoot = { dataset: Record<string, string | undefined> }
type ThemeStorage = { setItem(key: string, value: string): void; removeItem(key: string): void }

/**
 * Event-Theme anwenden oder wegnehmen. `target` nur für Tests; sonst Dokument
 * und localStorage. Schreibt nie `data-accent` oder die gespeicherten Einstellungen.
 */
export function applyEventTheme(
  events: readonly string[],
  target?: { root: ThemeRoot | null; storage: ThemeStorage | null },
): void {
  const root = target ? target.root : typeof document === 'undefined' ? null : document.documentElement
  const storage = target ? target.storage : typeof localStorage === 'undefined' ? null : localStorage
  const on = events.includes(HALLOWEEN_EVENT)
  if (root) {
    if (on) root.dataset.event = HALLOWEEN_EVENT
    else delete root.dataset.event
  }
  if (!storage) return
  try {
    if (on) storage.setItem(EVENT_STORAGE_KEY, HALLOWEEN_EVENT)
    else storage.removeItem(EVENT_STORAGE_KEY)
  } catch {
    // Privater Modus: der Splash bleibt dann beim normalen Bild.
  }
}

/** Minecraft-Name oder UUID (mit oder ohne Bindestriche) – wie `validate::target` im Kern. */
export function isEventPlayer(input: string): boolean {
  const s = input.trim()
  if (/^[A-Za-z0-9_]{1,16}$/.test(s)) return true
  if (/^[0-9a-fA-F]{32}$/.test(s)) return true
  return /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(s)
}

export interface WitchMotion {
  /** Füllkante 0…1. Unbestimmt: hin und her. */
  p: number
  /** Lampe nur bei 100 % (oder Dauerstrom). */
  lamp: boolean
  bob: number
  frame: number
}

/**
 * Stand der Hexe. Unbestimmter Fortschritt pendelt in 4 s von links nach rechts
 * und zurück (`halloween.js` läuft nur vorwärts, der Vertrag will hin und her).
 */
export function witchMotion(now: number, percent: number, indeterminate: boolean, powered: boolean): WitchMotion {
  let p: number
  if (powered) p = 1
  else if (indeterminate) {
    const period = 4000
    const u = (now % (period * 2)) / period
    p = u <= 1 ? u : 2 - u
  } else p = Math.min(1, Math.max(0, percent / 100))
  // Bestimmt: erst bei 100 %. Unbestimmt: kurz am rechten Wendepunkt (wie die Referenz p>0.97),
  // sonst wäre die Lampe nur einen Frame lang an.
  const lamp = powered || p >= (indeterminate ? 0.97 : 1)
  const bob = Math.round(Math.sin(now / WITCH_BOB_MS) * 2) * 2
  const frame = Math.floor(now / WITCH_FRAME_MS) % WITCH_FRAMES
  return { p, lamp, bob, frame }
}
