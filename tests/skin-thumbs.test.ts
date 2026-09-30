import { describe, expect, it } from 'vitest'
import { textHash, thumbKey } from '../app/utils/skinThumbs'

const skin = 'data:image/png;base64,AAAA'
const cape = 'data:image/png;base64,BBBB'

describe('Vorschaubild-Schlüssel', () => {
  it('hash ist stabil und hängt die Länge an', () => {
    expect(textHash('abc')).toBe(textHash('abc'))
    expect(textHash('abc')).not.toBe(textHash('abd'))
    expect(textHash('a')).toMatch(/^[0-9a-z]+\.1$/)
    expect(textHash('abcd')).toMatch(/\.4$/)
  })

  it('unterscheidet Armbreite, Umhang, Bildanzahl und Blickwinkel', () => {
    const front = thumbKey({ skin, variant: 'classic' })
    expect(thumbKey({ skin, variant: 'classic' })).toBe(front)
    expect(thumbKey({ skin, variant: 'slim' })).not.toBe(front)
    expect(thumbKey({ skin, variant: 'classic', view: 'back' })).not.toBe(front)
    const withCape = thumbKey({ skin, variant: 'classic', cape })
    expect(withCape).not.toBe(front)
    expect(thumbKey({ skin, variant: 'classic', cape, capeFrames: 4 })).not.toBe(withCape)
  })
})
