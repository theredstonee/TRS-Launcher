import { afterAll, describe, expect, it } from 'vitest'
import {
  clipKey,
  clipStem,
  failureText,
  formatClipDuration,
  isValidClipName,
  reasonText,
  recordingElapsed,
  usageShare,
} from '../app/utils/clips'
import { setLocale } from '../app/utils/i18n'
import { clipSettingsSchema } from '../app/utils/schemas'

const defaults = {
  enabled: false,
  bufferSeconds: 30,
  resolution: '1080p' as const,
  fps: 60 as const,
  quality: 'medium' as const,
  encoder: 'auto' as const,
  systemAudio: true,
  microphone: false,
  folder: null,
  maxStorageGb: 20,
}

describe('Clips-Helfer', () => {
  afterAll(() => setLocale('en'))

  it('formatiert Dauern wie ein Player', () => {
    expect(formatClipDuration(45_000)).toBe('0:45')
    expect(formatClipDuration(723_400)).toBe('12:03')
    expect(formatClipDuration(3_723_000)).toBe('1:02:03')
    expect(formatClipDuration(null)).toBe('–')
    expect(formatClipDuration(-5)).toBe('–')
  })

  it('rechnet den Speicheranteil begrenzt', () => {
    expect(usageShare(5, 10)).toBe(50)
    expect(usageShare(50, 10)).toBe(100)
    expect(usageShare(5, 0)).toBe(0)
  })

  it('prüft Dateinamen wie der Kern', () => {
    expect(isValidClipName('Bester Clip')).toBe(true)
    expect(isValidClipName('Bester Clip.mp4')).toBe(true)
    expect(isValidClipName('')).toBe(false)
    expect(isValidClipName('a/b')).toBe(false)
    expect(isValidClipName('con')).toBe(false)
    expect(isValidClipName('COM1')).toBe(false)
    expect(isValidClipName('.versteckt')).toBe(false)
    expect(isValidClipName('x'.repeat(200))).toBe(false)
    expect(clipStem('Clip.MP4')).toBe('Clip')
    expect(clipKey({ instanceId: 'a', fileName: 'b.mp4' })).toBe('a/b.mp4')
  })

  it('zählt die Aufnahmezeit selbst weiter', () => {
    expect(recordingElapsed(10_000, 1_000, 3_000)).toBe(12_000)
    expect(recordingElapsed(10_000, 5_000, 3_000)).toBe(10_000)
  })

  it('übersetzt Fehler und Gründe, unbekannte fallen zurück', async () => {
    await setLocale('de')
    expect(failureText('noWindow')).toContain('Spielfenster')
    expect(failureText('etwas-neues')).toBe(failureText('error'))
    expect(reasonText('ffmpeg')).toContain('FFmpeg')
    expect(reasonText(null)).toBe('Puffer aktiv')
    // Neue Gründe des Kerns haben eigene Texte (nicht den allgemeinen Fehler).
    expect(failureText('encoder')).toContain('x264')
    expect(failureText('ffmpegFailed')).toContain('FFmpeg')
    expect(reasonText('encoder')).not.toBe(reasonText('error'))
    expect(reasonText('ffmpegFailed')).not.toBe(reasonText('error'))
  })
})

describe('Clip-Einstellungen', () => {
  it('Standard ist gültig und aus', () => {
    expect(clipSettingsSchema.parse(defaults).enabled).toBe(false)
  })

  it('Grenzen wie im Kern', () => {
    expect(clipSettingsSchema.safeParse({ ...defaults, bufferSeconds: 14 }).success).toBe(false)
    expect(clipSettingsSchema.safeParse({ ...defaults, bufferSeconds: 121 }).success).toBe(false)
    expect(clipSettingsSchema.safeParse({ ...defaults, fps: 45 }).success).toBe(false)
    expect(clipSettingsSchema.safeParse({ ...defaults, maxStorageGb: 0 }).success).toBe(false)
    expect(clipSettingsSchema.safeParse({ ...defaults, encoder: 'quantum' }).success).toBe(false)
    expect(clipSettingsSchema.safeParse({ ...defaults, folder: 'D:\\Clips' }).success).toBe(true)
  })
})
