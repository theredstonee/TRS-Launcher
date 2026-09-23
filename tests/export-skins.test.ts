import { beforeAll, describe, expect, it } from 'vitest'
import { setLocale } from '../app/utils/i18n'
import { exportOptionsSchema, firstIssue, skinNameSchema } from '../app/utils/schemas'

// Die gleichen Regeln prüft der Kern noch einmal (modpack_export.rs, skins.rs);
// hier geht es darum, dass der Dialog früh und verständlich meckert.

beforeAll(() => setLocale('de'))

describe('exportOptionsSchema', () => {
  const valid = { name: 'Mein Pack', version: '1.0.0', summary: 'Kurz', include: ['mods', 'config'] }

  it('nimmt sinnvolle Eingaben an und schneidet Leerzeichen ab', () => {
    const parsed = exportOptionsSchema.parse({ ...valid, name: '  Mein Pack  ', summary: '  Kurz  ' })
    expect(parsed.name).toBe('Mein Pack')
    expect(parsed.summary).toBe('Kurz')
    expect(parsed.include).toEqual(['mods', 'config'])
  })

  it('erlaubt keine leeren Namen und keine Auswahl ohne Ordner', () => {
    expect(exportOptionsSchema.safeParse({ ...valid, name: '   ' }).success).toBe(false)
    const empty = exportOptionsSchema.safeParse({ ...valid, include: [] })
    expect(empty.success).toBe(false)
    if (!empty.success) expect(firstIssue(empty.error)).toContain('mindestens einen Ordner')
  })

  it('übersetzt Meldungen erst beim Prüfen – in der aktuellen Sprache', async () => {
    await setLocale('en')
    const tooLong = exportOptionsSchema.safeParse({ ...valid, name: 'x'.repeat(65) })
    expect(tooLong.success).toBe(false)
    if (!tooLong.success) expect(firstIssue(tooLong.error)).toBe('No more than 64 characters')
    await setLocale('de')
    const again = exportOptionsSchema.safeParse({ ...valid, name: 'x'.repeat(65) })
    if (!again.success) expect(firstIssue(again.error)).toBe('Maximal 64 Zeichen')
  })

  it('lässt in der Version nur unbedenkliche Zeichen zu', () => {
    for (const version of ['1.0.0', '2026.1-beta', 'v1_2+3']) {
      expect(exportOptionsSchema.safeParse({ ...valid, version }).success).toBe(true)
    }
    for (const version of ['1.0 beta', '../evil', '1/2', '']) {
      expect(exportOptionsSchema.safeParse({ ...valid, version }).success).toBe(false)
    }
  })

  it('akzeptiert eine leere Beschreibung nur als null', () => {
    expect(exportOptionsSchema.parse({ ...valid, summary: null }).summary).toBeNull()
    expect(exportOptionsSchema.safeParse({ ...valid, summary: 'x'.repeat(513) }).success).toBe(false)
  })
})

describe('skinNameSchema', () => {
  it('verlangt einen Namen ohne Steuerzeichen', () => {
    expect(skinNameSchema.parse('  Winter-Skin ')).toBe('Winter-Skin')
    expect(skinNameSchema.safeParse('').success).toBe(false)
    expect(skinNameSchema.safeParse('   ').success).toBe(false)
    expect(skinNameSchema.safeParse('a'.repeat(49)).success).toBe(false)
    expect(skinNameSchema.safeParse('böse\nZeile').success).toBe(false)
  })
})
