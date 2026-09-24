import { t, tKey, hasKey } from './i18n'
import type { ClipFailure, ClipReason } from '~/types'

/** Schlüssel eines Clips (Instanz + Datei). */
export function clipKey(clip: { instanceId: string; fileName: string }): string {
  return `${clip.instanceId}/${clip.fileName}`
}

/** "0:45", "12:03", "1:02:03"; unbekannt → "–". */
export function formatClipDuration(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return '–'
  const total = Math.round(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total / 60) % 60)
  const s = total % 60
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}

/** Anteil (0–100) des belegten Speichers am Limit. */
export function usageShare(used: number, limit: number): number {
  if (!(limit > 0)) return 0
  return Math.min(100, Math.max(0, (used / limit) * 100))
}

/** Dateiname ohne `.mp4` (zum Umbenennen). */
export function clipStem(fileName: string): string {
  return fileName.replace(/\.mp4$/i, '')
}

const RESERVED = /^(con|prn|aux|nul|com\d|lpt\d)$/i
const FORBIDDEN = /[\\/:*?"<>|]/
const CONTROL = /[\u0000-\u001f\u007f]/

/** Wie `trs_core::clips::library::is_clip_file_name` – der Kern prüft trotzdem selbst. */
export function isValidClipName(name: string): boolean {
  const stem = clipStem(name.trim())
  const full = `${stem}.mp4`
  return (
    stem.length > 0 &&
    full.length <= 180 &&
    !stem.startsWith('.') &&
    !stem.endsWith('.') &&
    !stem.endsWith(' ') &&
    !FORBIDDEN.test(stem) &&
    !CONTROL.test(stem) &&
    !RESERVED.test(stem.split('.')[0]!.trim())
  )
}

/** Übersetzte Meldung für einen Fehlercode des Rekorders. */
export function failureText(code: ClipFailure | string): string {
  const key = `clips.errors.${code}`
  return hasKey(key) ? tKey(key) : t('clips.errors.error')
}

/** Übersetzter Grund, warum (noch) nicht aufgenommen wird. */
export function reasonText(reason: ClipReason | string | null): string {
  if (!reason) return t('clips.status.buffer')
  const key = `clips.reason.${reason}`
  return hasKey(key) ? tKey(key) : t('clips.reason.error')
}

/** Laufzeit einer Aufnahme: Stand beim letzten Ereignis + seitdem vergangene Zeit. */
export function recordingElapsed(recordingMs: number, receivedAt: number, now: number): number {
  return Math.max(0, recordingMs + Math.max(0, now - receivedAt))
}
