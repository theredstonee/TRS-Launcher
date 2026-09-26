import { describe, expect, it } from 'vitest'
import {
  canPlayMp4,
  clipAssetUrl,
  filterClips,
  formatTimecode,
  keyframeAtOrBefore,
  neighbourClip,
  stepPlaybackRate,
  planTrim,
  playerKeyAction,
  setTrimPoint,
  stripFrameAt,
  timeAtRatio,
  trimmedName,
} from '../app/utils/clipPlayer'
import type { Clip, ClipStrip } from '../app/types'

function clip(fileName: string, extra: Partial<Clip> = {}): Clip {
  return {
    instanceId: 'survival',
    instanceName: 'Survival',
    fileName,
    size: 1000,
    createdAt: '2026-09-20T10:00:00Z',
    durationMs: 30_000,
    ...extra,
  }
}

describe('Clip-Adressen', () => {
  it('kodiert Instanz und Dateinamen – nie Pfade', () => {
    expect(clipAssetUrl('http://trsclip.localhost/', 'v', 'survival', 'Survival 2026-09-24 15-30-12.mp4')).toBe(
      'http://trsclip.localhost/v/survival/Survival%202026-09-24%2015-30-12.mp4',
    )
    expect(clipAssetUrl('trsclip://localhost', 'p', 'a', 'x#?.mp4')).toBe('trsclip://localhost/p/a/x%23%3F.mp4')
    // Schrägstriche im Namen bleiben kodiert (der Kern lehnt sie ab).
    expect(clipAssetUrl('trsclip://localhost/', 's', 'a', '../b.mp4')).toBe('trsclip://localhost/s/a/..%2Fb.mp4')
  })
})

describe('Zeitangaben', () => {
  it('formatiert Minuten, Stunden und Zehntel', () => {
    expect(formatTimecode(0)).toBe('0:00')
    expect(formatTimecode(5_340, true)).toBe('0:05.3')
    expect(formatTimecode(65_000)).toBe('1:05')
    expect(formatTimecode(3_723_000)).toBe('1:02:03')
    expect(formatTimecode(-5)).toBe('0:00')
    expect(formatTimecode(Number.NaN, true)).toBe('0:00.0')
  })

  it('Position auf der Zeitleiste', () => {
    expect(timeAtRatio(0.5, 30_000)).toBe(15_000)
    expect(timeAtRatio(-1, 30_000)).toBe(0)
    expect(timeAtRatio(2, 30_000)).toBe(30_000)
  })
})

describe('Vorschau-Leiste', () => {
  const strip: ClipStrip = { frames: 30, cols: 10, rows: 3, frameWidth: 160, frameHeight: 90, intervalMs: 1000, durationMs: 30_000 }
  it('findet das Bild zur Zeit im Raster', () => {
    expect(stripFrameAt(strip, 0)).toEqual({ index: 0, x: 0, y: 0 })
    expect(stripFrameAt(strip, 12_500)).toEqual({ index: 12, x: 320, y: 90 })
    expect(stripFrameAt(strip, 99_000)).toEqual({ index: 29, x: 1440, y: 180 })
    expect(stripFrameAt(strip, -3)).toEqual({ index: 0, x: 0, y: 0 })
  })
})

describe('Tastenkürzel', () => {
  it('wie in Video-Playern', () => {
    expect(playerKeyAction({ key: ' ' })).toEqual({ type: 'toggle' })
    expect(playerKeyAction({ key: 'K' })).toEqual({ type: 'toggle' })
    expect(playerKeyAction({ key: 'ArrowLeft' })).toEqual({ type: 'seek', deltaMs: -5000 })
    expect(playerKeyAction({ key: 'ArrowRight' })).toEqual({ type: 'seek', deltaMs: 5000 })
    expect(playerKeyAction({ key: 'j' })).toEqual({ type: 'seek', deltaMs: -10000 })
    expect(playerKeyAction({ key: 'l' })).toEqual({ type: 'seek', deltaMs: 10000 })
    expect(playerKeyAction({ key: 'f' })).toEqual({ type: 'fullscreen' })
    expect(playerKeyAction({ key: 'M' })).toEqual({ type: 'mute' })
    expect(playerKeyAction({ key: '5' })).toEqual({ type: 'seekTo', ratio: 0.5 })
    expect(playerKeyAction({ key: '.' })).toEqual({ type: 'frame', delta: 1 })
    expect(playerKeyAction({ key: '>', shiftKey: true })).toEqual({ type: 'speed', delta: 1 })
    expect(playerKeyAction({ key: 'Escape' })).toEqual({ type: 'close' })
  })

  it('I/O nur beim Zuschneiden, Systemkürzel nie', () => {
    expect(playerKeyAction({ key: 'i' })).toBeNull()
    expect(playerKeyAction({ key: 'i' }, true)).toEqual({ type: 'markIn' })
    expect(playerKeyAction({ key: 'o' }, true)).toEqual({ type: 'markOut' })
    expect(playerKeyAction({ key: 'f', ctrlKey: true })).toBeNull()
    expect(playerKeyAction({ key: 'ArrowRight', altKey: true })).toBeNull()
    expect(playerKeyAction({ key: 'x' })).toBeNull()
  })

  it('Tempo schaltet in Stufen', () => {
    expect(stepPlaybackRate(1, 1)).toBe(1.25)
    expect(stepPlaybackRate(1, -1)).toBe(0.75)
    expect(stepPlaybackRate(2, 1)).toBe(2)
    expect(stepPlaybackRate(0.25, -1)).toBe(0.25)
    expect(stepPlaybackRate(1.1, 1)).toBe(1.5)
  })
})

describe('Zuschneiden', () => {
  const info = { durationMs: 30_000, keyframesMs: [0, 2000, 4000, 6000, 8000] }

  it('Keyframe davor', () => {
    expect(keyframeAtOrBefore(info.keyframesMs, 5300)).toBe(4000)
    expect(keyframeAtOrBefore(info.keyframesMs, 4000)).toBe(4000)
    expect(keyframeAtOrBefore([], 900)).toBe(0)
  })

  it('kopiert auf Keyframes, kodiert sonst neu – wie der Kern', () => {
    expect(planTrim(info, 4000, 9000, 'auto')).toMatchObject({ startMs: 4000, method: 'copy', valid: true })
    expect(planTrim(info, 4100, 9000, 'auto')).toMatchObject({ startMs: 4000, method: 'copy' })
    expect(planTrim(info, 5300, 9000, 'auto')).toMatchObject({ startMs: 5300, method: 'reencode', leadMs: 1300 })
    expect(planTrim(info, 5300, 9000, 'fast')).toMatchObject({ startMs: 4000, method: 'copy', leadMs: 1300 })
    expect(planTrim(info, 4000, 9000, 'exact')).toMatchObject({ method: 'reencode' })
    expect(planTrim(info, 0, 900, 'auto').method).toBe('copy')
    expect(planTrim(info, 1000, 1400, 'auto').valid).toBe(false)
    expect(planTrim(info, 5000, 99_000, 'auto').endMs).toBe(30_000)
  })

  it('Start und Ende überholen sich nicht', () => {
    const r = { inMs: 0, outMs: 30_000 }
    expect(setTrimPoint(r, 'in', 10_000, 30_000)).toEqual({ inMs: 10_000, outMs: 30_000 })
    expect(setTrimPoint({ inMs: 0, outMs: 5000 }, 'in', 9000, 30_000)).toEqual({ inMs: 4500, outMs: 5000 })
    expect(setTrimPoint({ inMs: 8000, outMs: 30_000 }, 'out', 1000, 30_000)).toEqual({ inMs: 8000, outMs: 8500 })
    expect(setTrimPoint(r, 'out', 99_000, 30_000)).toEqual({ inMs: 0, outMs: 30_000 })
  })

  it('schlägt einen Namen vor', () => {
    expect(trimmedName('Survival 2026.mp4', 'gekürzt')).toBe('Survival 2026 (gekürzt)')
    expect(trimmedName(`${'x'.repeat(200)}.mp4`, 'cut').length).toBeLessThanOrEqual(170)
  })
})

describe('Galerie', () => {
  const list = [
    clip('b.mp4', { createdAt: '2026-09-21T10:00:00Z', durationMs: 5000, size: 300 }),
    clip('a.mp4', { createdAt: '2026-09-22T10:00:00Z', durationMs: 60_000, size: 100, instanceId: 'creative', instanceName: 'Kreativ Welt' }),
    clip('c 10.mp4', { createdAt: null, durationMs: null, size: 200 }),
    clip('c 9.mp4', { createdAt: '2026-09-20T10:00:00Z', size: 50 }),
  ]
  const names = (l: Clip[]) => l.map((c) => c.fileName)

  it('sortiert', () => {
    expect(names(filterClips(list, { sort: 'newest' }))).toEqual(['a.mp4', 'b.mp4', 'c 9.mp4', 'c 10.mp4'])
    expect(names(filterClips(list, { sort: 'oldest' }))).toEqual(['c 10.mp4', 'c 9.mp4', 'b.mp4', 'a.mp4'])
    expect(names(filterClips(list, { sort: 'longest' }))[0]).toBe('a.mp4')
    expect(names(filterClips(list, { sort: 'largest' }))).toEqual(['b.mp4', 'c 10.mp4', 'a.mp4', 'c 9.mp4'])
    expect(names(filterClips(list, { sort: 'name' }))).toEqual(['a.mp4', 'b.mp4', 'c 9.mp4', 'c 10.mp4'])
  })

  it('filtert nach Instanz und Suche (alle Wörter, auch im Instanznamen)', () => {
    expect(names(filterClips(list, { instanceId: 'creative' }))).toEqual(['a.mp4'])
    expect(names(filterClips(list, { query: 'kreativ' }))).toEqual(['a.mp4'])
    expect(names(filterClips(list, { query: 'survival c', sort: 'name' }))).toEqual(['c 9.mp4', 'c 10.mp4'])
    expect(filterClips(list, { query: 'nichts' })).toEqual([])
    expect(list.map((c) => c.fileName)).toEqual(['b.mp4', 'a.mp4', 'c 10.mp4', 'c 9.mp4'])
  })

  it('blättert rundherum', () => {
    expect(neighbourClip(list, list[0]!, 1)?.fileName).toBe('a.mp4')
    expect(neighbourClip(list, list[0]!, -1)?.fileName).toBe('c 9.mp4')
    expect(neighbourClip([], list[0]!, 1)).toBeNull()
    expect(neighbourClip(list, clip('weg.mp4'), 1)?.fileName).toBe('b.mp4')
  })
})

describe('Codecs', () => {
  it('erkennt fehlende H.264-Unterstützung', () => {
    expect(canPlayMp4(() => 'probably')).toBe(true)
    expect(canPlayMp4((t) => (t.includes('42E01E') ? 'maybe' : ''))).toBe(true)
    expect(canPlayMp4(() => '')).toBe(false)
  })
})
