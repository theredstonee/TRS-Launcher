// Start-Animation im Launcher: lokale Merker (Einstellungs-Spiegel, letzter Start), Jingle-Dateien, erneut abspielen.
// Der Motor selbst liegt framework-frei in utils/intro/ (auch für die eigenständige Vorschau).

import { ref } from 'vue'
import { pickIntroDesign, type IntroDesign } from './intro/designs'
import {
  INTRO_MEMORY_KEY,
  INTRO_PREFS_KEY,
  parseIntroMemory,
  parseIntroPrefs,
  type IntroMemory,
  type IntroPrefs,
} from './intro/select'
import type { IntroLength } from './intro/timeline'

// Jingles: eine Datei je Design in app/assets/intro/<design>.ogg (+ .mp3 als Rückfall für WebViews ohne Opus).
// Neue Datei hineinlegen genügt – die Liste entsteht beim Bauen.
const jingleFiles = import.meta.glob<string>('../assets/intro/*.{ogg,mp3}', { eager: true, query: '?url', import: 'default' })

function canPlayOpus(): boolean {
  try {
    return typeof Audio !== 'undefined' && new Audio().canPlayType('audio/ogg; codecs=opus') !== ''
  } catch {
    return false
  }
}

/** URL des Jingles zum Design oder null (dann nur Effekte). */
export function introJingleUrl(designId: string): string | null {
  const find = (ext: string) => Object.entries(jingleFiles).find(([path]) => path.endsWith(`/${designId}.${ext}`))?.[1] ?? null
  return (canPlayOpus() ? find('ogg') : null) ?? find('mp3') ?? find('ogg')
}

/** Einstellungs-Spiegel lesen (der Kern lädt die echten Einstellungen erst später). */
export function readIntroPrefs(): IntroPrefs {
  try {
    return parseIntroPrefs(localStorage.getItem(INTRO_PREFS_KEY))
  } catch {
    return parseIntroPrefs(null)
  }
}

/** Einstellungen spiegeln – aufgerufen, sobald sie geladen oder geändert sind. */
export function mirrorIntroPrefs(
  ui: { startIntro?: boolean; startIntroSound?: boolean; startIntroFlicker?: boolean; motion?: string } | undefined,
) {
  if (!ui) return
  try {
    const prefs: IntroPrefs = {
      enabled: ui.startIntro !== false,
      sound: ui.startIntroSound !== false,
      motion: ui.motion ?? 'full',
      flicker: ui.startIntroFlicker !== false,
    }
    localStorage.setItem(INTRO_PREFS_KEY, JSON.stringify(prefs))
  } catch {
    // Kein Speicher – beim nächsten Start gelten die Vorgaben.
  }
}

export function readIntroMemory(): IntroMemory | null {
  try {
    return parseIntroMemory(localStorage.getItem(INTRO_MEMORY_KEY))
  } catch {
    return null
  }
}

export function writeIntroMemory(m: IntroMemory) {
  try {
    localStorage.setItem(INTRO_MEMORY_KEY, JSON.stringify(m))
  } catch {
    // egal
  }
}

/** Aktive Variante beim Start: Event-/Design-Merker vom letzten Lauf (wie der Ladebildschirm). */
export function bootIntroDesign(): IntroDesign {
  const root = typeof document !== 'undefined' ? document.documentElement : null
  const events = root?.dataset.event ? [root.dataset.event] : []
  return pickIntroDesign({ events, design: root?.dataset.design ?? null })
}

/** Lief in dieser Sitzung schon eine Start-Animation? (Dann darf der Erst-Hinweis kommen.) */
export const introPlayedThisSession = ref(false)

/** „Jetzt abspielen“ aus den Einstellungen: Zähler, den die Start-Animation beobachtet. */
export const introReplay = ref<{ n: number; length: IntroLength }>({ n: 0, length: 'long' })

export function replayStartIntro(length: IntroLength = 'long') {
  introReplay.value = { n: introReplay.value.n + 1, length }
}
