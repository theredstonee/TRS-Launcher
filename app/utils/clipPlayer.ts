import type { Clip, ClipMediaInfo, ClipStrip, TrimMode, TrimMethod } from '~/types'

// Reine Logik für Clip-Galerie und Player (ohne Vue, ohne Tauri) – getestet in tests/clip-player.test.ts.

/** Art der Datei hinter dem Clip-Protokoll: Video, Vorschaubild, Vorschau-Leiste. */
export type ClipAsset = 'v' | 'p' | 's'

/**
 * Adresse im eigenen Protokoll `trsclip:` (nur Dateien im Clip-Ordner, der Kern prüft alles).
 * `base` = `convertFileSrc('', 'trsclip')`, also `http://trsclip.localhost/` (Windows) bzw. `trsclip://localhost/`.
 */
export function clipAssetUrl(base: string, asset: ClipAsset, instanceId: string, fileName: string): string {
  const root = base.endsWith('/') ? base : `${base}/`
  return `${root}${asset}/${encodeURIComponent(instanceId)}/${encodeURIComponent(fileName)}`
}

/** Zeitangabe im Player: „0:05“, „1:02:03“, mit `tenths` „0:05.3“. */
export function formatTimecode(ms: number, tenths = false): string {
  if (!Number.isFinite(ms) || ms < 0) ms = 0
  const totalTenths = Math.floor(ms / 100)
  const total = Math.floor(totalTenths / 10)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total / 60) % 60)
  const s = total % 60
  const ss = String(s).padStart(2, '0')
  const base = h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
  return tenths ? `${base}.${totalTenths % 10}` : base
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

/** Position in der Zeitleiste (0–1) → Zeit in ms. */
export function timeAtRatio(ratio: number, durationMs: number): number {
  return Math.round(clamp(ratio, 0, 1) * Math.max(0, durationMs))
}

/** Welches Bild der Vorschau-Leiste zu dieser Zeit gehört, und wo es im Raster liegt (Pixel). */
export function stripFrameAt(strip: ClipStrip, ms: number): { index: number; x: number; y: number } {
  const index = clamp(Math.floor(ms / Math.max(1, strip.intervalMs)), 0, Math.max(0, strip.frames - 1))
  return { index, x: (index % strip.cols) * strip.frameWidth, y: Math.floor(index / strip.cols) * strip.frameHeight }
}

// --- Tastatur -----------------------------------------------------------------------

export type PlayerAction =
  | { type: 'toggle' }
  | { type: 'pause' }
  | { type: 'seek'; deltaMs: number }
  | { type: 'seekTo'; ratio: number }
  | { type: 'frame'; delta: 1 | -1 }
  | { type: 'fullscreen' }
  | { type: 'mute' }
  | { type: 'volume'; delta: number }
  | { type: 'speed'; delta: 1 | -1 }
  | { type: 'markIn' }
  | { type: 'markOut' }
  | { type: 'close' }

export interface KeyLike {
  key: string
  shiftKey?: boolean
  ctrlKey?: boolean
  altKey?: boolean
  metaKey?: boolean
}

/**
 * Tasten wie in gängigen Video-Playern: Leertaste/K Pause, ←/→ 5 s, J/L 10 s,
 * F Vollbild, M Ton aus, ↑/↓ Lautstärke, ,/. Einzelbild, Umschalt+,/. Tempo,
 * 0–9 springt auf 0–90 %, Pos1/Ende, I/O setzen Start/Ende beim Zuschneiden.
 * Mit Strg/Alt/Meta → nichts (Kürzel des Systems bzw. Alt+←/→ = anderer Clip).
 */
export function playerKeyAction(e: KeyLike, trimming = false): PlayerAction | null {
  if (e.ctrlKey || e.altKey || e.metaKey) return null
  const key = e.key.length === 1 ? e.key.toLowerCase() : e.key
  if (e.shiftKey && (key === ',' || key === '<')) return { type: 'speed', delta: -1 }
  if (e.shiftKey && (key === '.' || key === '>')) return { type: 'speed', delta: 1 }
  switch (key) {
    case ' ':
    case 'k':
      return { type: 'toggle' }
    case 'ArrowLeft':
      return { type: 'seek', deltaMs: -5000 }
    case 'ArrowRight':
      return { type: 'seek', deltaMs: 5000 }
    case 'j':
      return { type: 'seek', deltaMs: -10000 }
    case 'l':
      return { type: 'seek', deltaMs: 10000 }
    case ',':
      return { type: 'frame', delta: -1 }
    case '.':
      return { type: 'frame', delta: 1 }
    case 'f':
      return { type: 'fullscreen' }
    case 'm':
      return { type: 'mute' }
    case 'ArrowUp':
      return { type: 'volume', delta: 0.1 }
    case 'ArrowDown':
      return { type: 'volume', delta: -0.1 }
    case 'Home':
      return { type: 'seekTo', ratio: 0 }
    case 'End':
      return { type: 'seekTo', ratio: 1 }
    case 'i':
      return trimming ? { type: 'markIn' } : null
    case 'o':
      return trimming ? { type: 'markOut' } : null
    case 'Escape':
      return { type: 'close' }
  }
  if (/^[0-9]$/.test(key)) return { type: 'seekTo', ratio: Number(key) / 10 }
  return null
}

/** Wiedergabe-Tempi zum Durchschalten. */
export const PLAYBACK_RATES: readonly number[] = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 2]

/** Nächstes bzw. vorheriges Tempo aus {@link PLAYBACK_RATES}. */
export function stepPlaybackRate(current: number, delta: 1 | -1): number {
  const i = PLAYBACK_RATES.findIndex((r) => r >= current - 1e-6)
  const at = i < 0 ? PLAYBACK_RATES.length - 1 : i
  return PLAYBACK_RATES[clamp(at + delta, 0, PLAYBACK_RATES.length - 1)]!
}

// --- Zuschneiden --------------------------------------------------------------------

/** Wie im Kern (`clips::edit`). */
export const KEYFRAME_TOLERANCE_MS = 120
export const MIN_TRIM_MS = 500

export function keyframeAtOrBefore(keyframes: readonly number[], ms: number): number {
  let lo = 0
  let hi = keyframes.length - 1
  let best = 0
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    if (keyframes[mid]! <= ms) {
      best = keyframes[mid]!
      lo = mid + 1
    } else hi = mid - 1
  }
  return best
}

export interface TrimPreview {
  startMs: number
  endMs: number
  method: TrimMethod
  /** Wie weit „schnell“ den Start nach vorn verschieben würde (ms). */
  leadMs: number
  valid: boolean
}

/**
 * Spiegel von `clips::edit::plan_trim` für die Erklärung in der Oberfläche
 * (kopieren = sofort; neu kodieren nur, wenn der Start zwischen zwei Keyframes liegt).
 */
export function planTrim(info: Pick<ClipMediaInfo, 'durationMs' | 'keyframesMs'>, startMs: number, endMs: number, mode: TrimMode): TrimPreview {
  const end = Math.min(endMs, info.durationMs)
  const start = Math.max(0, startMs)
  const keyframe = keyframeAtOrBefore(info.keyframesMs, start)
  const leadMs = start - keyframe
  const onKeyframe = start === 0 || (info.keyframesMs.length > 0 && leadMs <= KEYFRAME_TOLERANCE_MS)
  const valid = info.durationMs > 0 && end > start && end - start >= MIN_TRIM_MS
  if (mode === 'exact') return { startMs: start, endMs: end, method: 'reencode', leadMs, valid }
  if (mode === 'fast' || onKeyframe) return { startMs: keyframe, endMs: end, method: 'copy', leadMs, valid }
  return { startMs: start, endMs: end, method: 'reencode', leadMs, valid }
}

/** Start/Ende setzen, ohne dass sie sich überholen (mindestens MIN_TRIM_MS Abstand, wo möglich). */
export function setTrimPoint(range: { inMs: number; outMs: number }, which: 'in' | 'out', ms: number, durationMs: number): { inMs: number; outMs: number } {
  const t = clamp(Math.round(ms), 0, durationMs)
  if (which === 'in') {
    const inMs = Math.min(t, Math.max(0, range.outMs - MIN_TRIM_MS))
    return { inMs, outMs: range.outMs }
  }
  const outMs = Math.max(t, Math.min(durationMs, range.inMs + MIN_TRIM_MS))
  return { inMs: range.inMs, outMs }
}

/** Vorschlag für den Namen des neuen Clips: „<Name> (<Zusatz>)“, gekürzt auf gültige Länge. */
export function trimmedName(fileName: string, suffix: string): string {
  const stem = fileName.replace(/\.mp4$/i, '')
  return `${stem} (${suffix})`.slice(0, 170).trim()
}

// --- Galerie ------------------------------------------------------------------------

export type ClipSort = 'newest' | 'oldest' | 'longest' | 'largest' | 'name'
export const CLIP_SORTS: readonly ClipSort[] = ['newest', 'oldest', 'longest', 'largest', 'name']

export interface ClipFilter {
  instanceId?: string | null
  query?: string
  sort?: ClipSort
}

function time(clip: Clip): number {
  return clip.createdAt ? Date.parse(clip.createdAt) || 0 : 0
}

/** Suche (Name, Instanz), Instanz-Filter und Sortierung. */
export function filterClips(clips: readonly Clip[], filter: ClipFilter): Clip[] {
  const q = (filter.query ?? '').trim().toLocaleLowerCase()
  const words = q ? q.split(/\s+/) : []
  const out = clips.filter((c) => {
    if (filter.instanceId && c.instanceId !== filter.instanceId) return false
    if (!words.length) return true
    const hay = `${c.fileName} ${c.instanceName}`.toLocaleLowerCase()
    return words.every((w) => hay.includes(w))
  })
  const byName = (a: Clip, b: Clip) => a.fileName.localeCompare(b.fileName, undefined, { numeric: true, sensitivity: 'base' })
  switch (filter.sort ?? 'newest') {
    case 'newest':
      return out.sort((a, b) => time(b) - time(a) || byName(a, b))
    case 'oldest':
      return out.sort((a, b) => time(a) - time(b) || byName(a, b))
    case 'longest':
      return out.sort((a, b) => (b.durationMs ?? -1) - (a.durationMs ?? -1) || time(b) - time(a))
    case 'largest':
      return out.sort((a, b) => b.size - a.size || time(b) - time(a))
    case 'name':
      return out.sort(byName)
  }
}

/** Nächster/vorheriger Clip in der sichtbaren Liste (rundherum). */
export function neighbourClip(list: readonly Clip[], current: Clip, delta: number): Clip | null {
  if (!list.length) return null
  const index = list.findIndex((c) => c.instanceId === current.instanceId && c.fileName === current.fileName)
  if (index < 0) return list[0] ?? null
  return list[(index + delta + list.length * 2) % list.length] ?? null
}

/** Spielt dieses Webview H.264/AAC in MP4? (`canPlayType` liefert "", "maybe" oder "probably".) */
export function canPlayMp4(canPlayType: (type: string) => string): boolean {
  return canPlayType('video/mp4; codecs="avc1.640028, mp4a.40.2"') !== '' || canPlayType('video/mp4; codecs="avc1.42E01E"') !== ''
}
