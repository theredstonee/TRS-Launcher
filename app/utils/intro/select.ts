// Welche Fassung der Start-Animation läuft? (rein, testbar)
// - nach einem Update (andere Version als beim letzten Start) bzw. beim allerersten Start: lang (~10 s)
// - erster Start des Tages: mittel (~7 s)
// - sonst: kurz (~4 s); Handys immer kurz.
// Gemerkt wird lokal (localStorage), damit die Wahl sofort beim Start feststeht – ohne auf den Kern zu warten.

import type { IntroLength } from './timeline'

export const INTRO_MEMORY_KEY = 'trs.intro.last'
/** Spiegel der Einstellungen für den Start (Einstellungen kommen erst später aus dem Kern). */
export const INTRO_PREFS_KEY = 'trs-intro'

export interface IntroMemory {
  /** Lokaler Tag des letzten Starts (JJJJ-MM-TT). */
  day: string
  /** App-Version beim letzten Start. */
  version: string
}

export interface IntroPrefs {
  enabled: boolean
  sound: boolean
  /** Einstellung „Animationen“ (full | system | reduced). */
  motion: string
  /** Flackern und Lichtblitze (aus = ruhige Fassung). */
  flicker: boolean
}

export const DEFAULT_INTRO_PREFS: IntroPrefs = { enabled: true, sound: true, motion: 'full', flicker: true }

/** Lokaler Kalendertag. */
export function introDay(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

export function parseIntroMemory(raw: string | null | undefined): IntroMemory | null {
  if (!raw) return null
  try {
    const v = JSON.parse(raw) as Partial<IntroMemory>
    if (typeof v?.day !== 'string' || typeof v?.version !== 'string') return null
    return { day: v.day, version: v.version }
  } catch {
    return null
  }
}

export function parseIntroPrefs(raw: string | null | undefined): IntroPrefs {
  if (!raw) return { ...DEFAULT_INTRO_PREFS }
  try {
    const v = JSON.parse(raw) as Partial<IntroPrefs>
    return {
      enabled: v?.enabled !== false,
      sound: v?.sound !== false,
      motion: typeof v?.motion === 'string' ? v.motion : DEFAULT_INTRO_PREFS.motion,
      flicker: v?.flicker !== false,
    }
  } catch {
    return { ...DEFAULT_INTRO_PREFS }
  }
}

export interface IntroLengthInput {
  last: IntroMemory | null
  today: string
  /** Aktuelle Version (leer = unbekannt, z. B. im Browser). */
  version: string
  mobile?: boolean
}

export function pickIntroLength(o: IntroLengthInput): IntroLength {
  if (o.mobile) return 'short'
  if (!o.last) return 'long'
  if (o.version && o.last.version && o.version !== o.last.version) return 'long'
  if (o.last.day !== o.today) return 'medium'
  return 'short'
}

/**
 * Läuft die Animation? Nur die kurze 4-s-Fassung lässt sich abschalten – die Fassung zum ersten Start des Tages
 * (mittel) und nach Updates (lang) laufen immer (überspringbar per Klick).
 */
export function shouldPlayIntro(length: IntroLength, enabled: boolean): boolean {
  return length !== 'short' || enabled
}

/**
 * Erst-Hinweis („Soll die Animation bei jedem Start laufen?“): einmal, nachdem in dieser Sitzung wirklich eine
 * Animation lief, und erst wenn die Einstellungen geladen sind.
 */
export function shouldShowIntroHint(o: { loaded: boolean; seen: boolean | undefined; playedThisSession: boolean }): boolean {
  return o.loaded && o.seen !== true && o.playedThisSession
}

/** Reduzierte Bewegung aus Einstellung + System (wie `isReducedMotion`). */
export function introReduced(motion: string, systemReduced: boolean): boolean {
  return motion === 'reduced' || (motion === 'system' && systemReduced)
}
