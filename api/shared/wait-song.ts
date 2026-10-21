/**
 * Persönliches Musikvideo „Freitag um sechs am Abend“ (Creator-Dashboard, Wunsch des Owners) – gemeinsame Teile für
 * Server und Website: wer es sehen darf, das Zeitraster (timeline.json) und die Auswahl der Szene nach der Zeit.
 * Keine Importe (läuft im Browser und im Server).
 *
 * timeline.json (liegt privat neben der MP3 unter `<PRIVATE_ASSETS_DIR>/wait-song/`): Startzeit jedes Liedteils in
 * Sekunden, aufsteigend, plus `end` (Ende des Lieds). Optional `beats: { bpm, offset }` (Tempo + erster Schlag in
 * Sekunden) für Effekte im Takt. Beispiel:
 *   { "intro": 0, "verse1": 9.6, "pre1": 38.4, "chorus1": 48, "verse2": 67.2, "pre2": 96, "chorus2": 105.6,
 *     "bridge": 124.8, "final": 144, "outro": 163.2, "end": 182, "beats": { "bpm": 100, "offset": 0.42 } }
 * `beats.offset` sollte auf einer EINS (Taktanfang) liegen – Schnitte und Effekte rasten auf Takte ein.
 * Optional `cues`: Momente innerhalb der Teile (Sekunden, absolut), fehlende werden aus den Teilen geschätzt:
 *   finalStop (Pause im letzten Refrain), finalHit (Einsatz danach), launch (Raketenstart nach dem Countdown),
 *   fade (Ausklang beginnt), swell (letztes Aufschwellen vor dem Ende).
 */

/** Minecraft-UUID (ohne Striche) des Creators, für den das Video gemacht ist. */
export const WAIT_SONG_UUID = '889912aeb76346ce9911a1ce629540a5'

/** Liedteile in Reihenfolge. */
export const WAIT_SECTIONS = ['intro', 'verse1', 'pre1', 'chorus1', 'verse2', 'pre2', 'chorus2', 'bridge', 'final', 'outro'] as const
export type WaitSection = (typeof WAIT_SECTIONS)[number]

export interface WaitBeats {
  /** Schläge pro Minute (40–240). */
  bpm: number
  /** Zeitpunkt des ersten Schlags in Sekunden. */
  offset: number
}

export const WAIT_CUES = ['finalStop', 'finalHit', 'launch', 'fade', 'swell'] as const
export type WaitCue = (typeof WAIT_CUES)[number]

export type WaitTimeline = Record<WaitSection, number> & { end: number, beats?: WaitBeats, cues?: Partial<Record<WaitCue, number>> }

/** Längstes erlaubtes Lied (Sekunden). */
export const WAIT_MAX_LENGTH = 15 * 60

const num = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v)

/**
 * Prüft ein Zeitraster (z. B. aus timeline.json). `intro` darf fehlen (= 0). Alle Werte ≥ 0, streng aufsteigend,
 * `end` ≤ 15 min. Unbekannte Schlüssel werden ignoriert. Ungültig → `null`.
 */
export function parseWaitTimeline(raw: unknown): WaitTimeline | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const o = raw as Record<string, unknown>
  const out: Partial<Record<WaitSection | 'end', number>> = {}
  let prev = -1
  for (const key of [...WAIT_SECTIONS, 'end'] as const) {
    const v = key === 'intro' && o.intro === undefined ? 0 : o[key]
    if (!num(v) || v < 0 || v <= prev || v > WAIT_MAX_LENGTH) return null
    out[key] = Math.round(v * 1000) / 1000
    prev = v
  }
  const tl = out as WaitTimeline
  if (o.beats !== undefined) {
    const b = o.beats as Record<string, unknown> | null
    if (!b || typeof b !== 'object' || !num(b.bpm) || b.bpm < 40 || b.bpm > 240) return null
    const offset = b.offset === undefined ? 0 : b.offset
    if (!num(offset) || offset < 0 || offset > 60) return null
    tl.beats = { bpm: b.bpm, offset }
  }
  if (o.cues !== undefined) {
    const c = o.cues as Record<string, unknown> | null
    if (!c || typeof c !== 'object' || Array.isArray(c)) return null
    const cues: Partial<Record<WaitCue, number>> = {}
    for (const key of WAIT_CUES) {
      const v = c[key]
      if (v === undefined) continue
      if (!num(v) || v < 0 || v > tl.end) return null
      cues[key] = Math.round(v * 1000) / 1000
    }
    tl.cues = cues
  }
  return tl
}

export interface SectionAt {
  key: WaitSection
  index: number
  start: number
  end: number
  /** Sekunden seit Beginn des Teils. */
  t: number
  /** Fortschritt im Teil 0…1. */
  p: number
}

/** Liedteil zur Zeit `t` (vor 0 → intro, nach `end` → outro mit p = 1). */
export function sectionAt(tl: WaitTimeline, t: number): SectionAt {
  let index = 0
  for (let i = WAIT_SECTIONS.length - 1; i >= 0; i--) {
    if (t >= tl[WAIT_SECTIONS[i]!]) {
      index = i
      break
    }
  }
  const key = WAIT_SECTIONS[index]!
  const start = tl[key]
  const end = index + 1 < WAIT_SECTIONS.length ? tl[WAIT_SECTIONS[index + 1]!] : tl.end
  const local = Math.max(0, t - start)
  return { key, index, start, end, t: local, p: Math.min(1, local / Math.max(0.001, end - start)) }
}

export interface BeatAt {
  bpm: number
  /** Schläge seit dem ersten (kann negativ sein). */
  count: number
  /** Stelle im Schlag 0…1. */
  phase: number
  /** Nummer des aktuellen Schlags. */
  index: number
}

/** Takt zur Zeit `t`. Ohne `beats`: 100 BPM ab Strophe 1 (passend zum Suno-Prompt). */
export function beatAt(tl: WaitTimeline, t: number): BeatAt {
  const bpm = tl.beats?.bpm ?? 100
  const offset = tl.beats?.offset ?? tl.verse1
  const count = (t - offset) * bpm / 60
  const index = Math.floor(count)
  return { bpm, count, phase: count - index, index }
}
