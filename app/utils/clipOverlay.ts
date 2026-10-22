import { t } from './i18n'
import { failureText, formatClipDuration } from './clips'
import type { ClipEvent, ClipState } from '~/types'

// Inhalt des Clip-Overlays (eigenes, aus der Aufnahme ausgeschlossenes Launcher-Fenster über dem
// Spiel): „● REC m:ss“, „○ Puffer 30 s“ und kurze Clip-Meldungen. Reine Funktionen – der Store
// (zeigen/verstecken) und die Overlay-Seite (Inhalt) entscheiden damit gleich.

/** So lange steht eine Meldung im Overlay (ms). */
export const OVERLAY_NOTE_MS = 3000

/** Was das Overlay für ein Spiel braucht (Auszug aus dem Clip-Stand). */
export type OverlayState = Pick<ClipState, 'instanceId' | 'buffer' | 'recording' | 'clipSeconds' | 'overlay' | 'bufferHud'>

/** Obere Zeile: Aufnahme läuft, Puffer läuft – oder nichts. */
export type OverlayLine = { kind: 'rec' } | { kind: 'buffer'; seconds: number } | null

/** Farbe einer Meldung: Erfolg grün, Hinweis gelb, Fehler rot. */
export type OverlayTone = 'ok' | 'warn' | 'error'

export type OverlayNote =
  | { instanceId: string; kind: 'clipSaved' | 'recordingSaved'; seconds: number }
  | { instanceId: string; kind: 'recordingStarted' }
  | { instanceId: string; kind: 'failed'; code: string }

/**
 * Obere Zeile für dieses Spiel. „REC“ zeigt das Overlay immer (die Mod zeichnet es seit dem
 * REC-Overlay nie mehr ins Spiel); den Puffer nur, wenn die Mod ihn dem Launcher überlässt
 * (`overlay`) und der Spieler ihn sehen will (`bufferHud`) – ältere Mods zeichnen ihn selbst.
 */
export function overlayLine(state: OverlayState | undefined): OverlayLine {
  if (!state) return null
  if (state.recording) return { kind: 'rec' }
  if (state.buffer && state.overlay && state.bufferHud) return { kind: 'buffer', seconds: state.clipSeconds }
  return null
}

/** Fehler, die die Mod weiter selbst zeigt (Clips aus: dort gibt es das Angebot „noch einmal = einschalten“). */
const GAME_HANDLES = new Set(['disabled'])

/**
 * Meldung für das Overlay zu einem Ereignis, oder null. `previous` = Stand des Spiels vor diesem
 * Ereignis (für „Aufnahme läuft“ beim Übergang). Nur für Spiele, deren Mod die Meldungen dem
 * Launcher überlässt – sonst stünden sie doppelt da (ältere Mods zeichnen sie selbst).
 */
export function overlayNoteFor(
  event: ClipEvent,
  previous: OverlayState | undefined,
): OverlayNote | null {
  switch (event.type) {
    case 'state':
      if (event.overlay && event.recording && previous && !previous.recording) {
        return { instanceId: event.instanceId, kind: 'recordingStarted' }
      }
      return null
    case 'saved':
      if (!previous?.overlay) return null
      return {
        instanceId: event.instanceId,
        kind: event.kind === 'recording' ? 'recordingSaved' : 'clipSaved',
        seconds: Math.max(0, Math.round(event.seconds)),
      }
    case 'failed':
      if (!previous?.overlay || GAME_HANDLES.has(event.code)) return null
      return { instanceId: event.instanceId, kind: 'failed', code: event.code }
    default:
      return null
  }
}

/** „30 s“ unter einer Minute, sonst „1:05“ – wie bisher im Spiel. */
export function overlayDuration(seconds: number): string {
  const s = Math.max(0, Math.round(seconds))
  return s < 60 ? t('clips.overlay.seconds', { n: s }) : formatClipDuration(s * 1000)
}

/** Text der oberen Zeile („REC 0:42“ bzw. „Puffer 30 s“); `elapsedMs` = laufende Aufnahmezeit. */
export function overlayLineText(line: Exclude<OverlayLine, null>, elapsedMs: number): string {
  if (line.kind === 'rec') return `REC ${formatClipDuration(Math.floor(Math.max(0, elapsedMs) / 1000) * 1000)}`
  return t('clips.overlay.buffer', { duration: overlayDuration(line.seconds) })
}

export function overlayNoteText(note: OverlayNote): string {
  switch (note.kind) {
    case 'clipSaved':
      return t('clips.overlay.clipSaved', { duration: overlayDuration(note.seconds) })
    case 'recordingSaved':
      return t('clips.overlay.recordingSaved', { duration: overlayDuration(note.seconds) })
    case 'recordingStarted':
      return t('clips.overlay.recordingStarted')
    case 'failed':
      return failureText(note.code)
  }
}

/** Vorübergehende Lagen (gleich nochmal versuchen) gelb, echte Fehler rot. */
const SOFT_FAILURES = new Set(['starting', 'ffmpeg', 'noFrames', 'busy'])

export function overlayNoteTone(note: OverlayNote): OverlayTone {
  if (note.kind !== 'failed') return 'ok'
  return SOFT_FAILURES.has(note.code) ? 'warn' : 'error'
}

/**
 * Welches Spiel bekommt das (einzige) Overlay? Erst ein aufnehmendes, dann eins mit
 * Puffer-Anzeige, dann eins mit einer noch stehenden Meldung (`notes`: Instanz → sichtbar bis, ms).
 * null = nichts zu zeigen → Overlay verstecken.
 */
export function overlayInstance(
  states: readonly OverlayState[],
  notes: Readonly<Record<string, number>>,
  now: number,
): string | null {
  const lines = states.map((s) => [s, overlayLine(s)] as const)
  const rec = lines.find(([, l]) => l?.kind === 'rec')
  if (rec) return rec[0].instanceId
  const buffer = lines.find(([, l]) => l?.kind === 'buffer')
  if (buffer) return buffer[0].instanceId
  const known = new Set(states.map((s) => s.instanceId))
  const noted = Object.entries(notes).find(([id, until]) => until > now && known.has(id))
  return noted?.[0] ?? null
}
