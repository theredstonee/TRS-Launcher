import { describe, expect, it } from 'vitest'
import {
  canRequestPet,
  checkPetCodes,
  checkPetRequest,
  creatorEventSchemas,
  creatorPetStateSchema,
  petCodeLabel,
  petCodeSchema,
  petCodeStatus,
  petCodesFit,
  petImageKind,
  petImageProblem,
  petLimitPercent,
  petLimitResetsAt,
  petMonthDate,
  petRemaining,
  visibleTabs,
  type PetCodesForm,
} from '../app/utils/creator'

// Creator-Haustier: Monatslimit, Code-Eingaben, Status der Codes, Antrag und
// Referenzbild – reine Regeln aus app/utils/creator.ts.

const NOW = Date.parse('2026-10-25T12:00:00.000Z')
const form = (f: Partial<PetCodesForm> = {}): PetCodesForm => ({ count: 1, maxUses: 10, until: '', note: '', ...f })

describe('Monatslimit', () => {
  it('rechnet Rest und Balken', () => {
    expect(petRemaining({ used: 190, max: 200 })).toBe(10)
    expect(petRemaining({ used: 230, max: 200 })).toBe(0)
    expect(petRemaining(null)).toBe(0)
    expect(petLimitPercent({ used: 50, max: 200 })).toBe(25)
    expect(petLimitPercent({ used: 300, max: 200 })).toBe(100)
    expect(petLimitPercent({ used: 0, max: 0 })).toBe(0)
  })

  it('kennt Monat und Neustart am Ersten', () => {
    const reset = petLimitResetsAt('2026-10')!
    expect([reset.getFullYear(), reset.getMonth(), reset.getDate()]).toEqual([2026, 10, 1])
    const dec = petLimitResetsAt('2026-12')!
    expect([dec.getFullYear(), dec.getMonth()]).toEqual([2027, 0])
    expect(petMonthDate('2026-10')!.getMonth()).toBe(9)
    expect(petLimitResetsAt('2026-13')).toBeNull()
    expect(petLimitResetsAt(null)).toBeNull()
  })
})

describe('Codes erstellen', () => {
  it('prüft Anzahl, Einlösungen, Datum, Notiz und Limit', () => {
    expect(checkPetCodes(form(), 10, NOW)).toBeNull()
    expect(checkPetCodes(form({ count: 0 }), 100, NOW)).toBe('count')
    expect(checkPetCodes(form({ count: 21 }), 1000, NOW)).toBe('count')
    expect(checkPetCodes(form({ count: '' }), 100, NOW)).toBe('count')
    expect(checkPetCodes(form({ maxUses: 101 }), 1000, NOW)).toBe('uses')
    expect(checkPetCodes(form({ maxUses: 2.5 }), 1000, NOW)).toBe('uses')
    expect(checkPetCodes(form({ until: '2020-01-01' }), 100, NOW)).toBe('until')
    expect(checkPetCodes(form({ until: '2099-01-01' }), 100, NOW)).toBeNull()
    expect(checkPetCodes(form({ note: 'x'.repeat(201) }), 100, NOW)).toBe('note')
    // Die Summe zählt: 3 × 10 = 30 > 20.
    expect(checkPetCodes(form({ count: 3, maxUses: 10 }), 20, NOW)).toBe('limit')
    expect(checkPetCodes(form({ count: 2, maxUses: 10 }), 20, NOW)).toBeNull()
  })

  it('sagt, wie viele Codes noch passen', () => {
    expect(petCodesFit(25, 10)).toBe(2)
    expect(petCodesFit(1000, 1)).toBe(20)
    expect(petCodesFit(5, 10)).toBe(0)
    expect(petCodesFit(5, 0)).toBe(0)
  })
})

describe('Codes in der Liste', () => {
  const base = { revoked: false, uses: 0, maxUses: 5, expiresAt: null }
  it('Status', () => {
    expect(petCodeStatus(base, NOW)).toBe('active')
    expect(petCodeStatus({ ...base, revoked: true, uses: 5 }, NOW)).toBe('revoked')
    expect(petCodeStatus({ ...base, uses: 5 }, NOW)).toBe('usedUp')
    expect(petCodeStatus({ ...base, expiresAt: '2026-10-01T00:00:00.000Z' }, NOW)).toBe('expired')
  })

  it('zeigt Klartext nur, wenn er da ist', () => {
    expect(petCodeLabel({ code: '7K3QF-M2XPA', codeHint: 'M2XPA' })).toBe('7K3QF-M2XPA')
    expect(petCodeLabel({ code: null, codeHint: 'C4HJN' })).toBe('…C4HJN')
    expect(petCodeLabel({ code: null, codeHint: '…C4HJN' })).toBe('…C4HJN')
  })

  it('Schema aus dem Kern', () => {
    const c = petCodeSchema.parse({ id: '1', code: null, codeHint: 'C4HJN', maxUses: 10, uses: 4, revoked: false, expiresAt: null, note: null, createdAt: null })
    expect(c.uses).toBe(4)
    expect(() => petCodeSchema.parse({ ...c, code: '<script>' })).toThrow()
    const s = creatorPetStateSchema.parse({ pet: { cosmeticId: 'penguin', name: 'Pinguin', card: null, count: 2, available: true }, limit: { month: '2026-10', used: 3, max: 200, resetsAt: null }, request: null, canRequest: false })
    expect(s.pet?.cosmeticId).toBe('penguin')
    expect(() => creatorPetStateSchema.parse({ ...s, pet: { ...s.pet, card: 'https://evil.example/x.png' } })).toThrow()
  })
})

describe('Antrag', () => {
  const request = (status: 'open' | 'in_progress' | 'done' | 'rejected') => ({ id: '1', status, species: 'Fuchs', text: 'Mit Schal', colors: null, hasImage: false, answer: null, createdAt: null })
  it('ein offener oder laufender Antrag sperrt einen neuen', () => {
    expect(canRequestPet({ pet: null, request: null })).toBe(true)
    expect(canRequestPet({ pet: null, request: request('open') })).toBe(false)
    expect(canRequestPet({ pet: null, request: request('in_progress') })).toBe(false)
    expect(canRequestPet({ pet: null, request: request('rejected') })).toBe(true)
    expect(canRequestPet({ pet: { cosmeticId: 'penguin', name: 'Pinguin', card: null, count: 1, available: true }, request: null })).toBe(false)
  })

  it('Referenzbild: PNG oder JPG bis 2 MB, auch nach den ersten Bytes', () => {
    expect(petImageProblem({ type: 'image/png', size: 1024 })).toBeNull()
    expect(petImageProblem({ type: 'image/jpeg', size: 2 * 1024 * 1024 })).toBeNull()
    expect(petImageProblem({ type: 'image/gif', size: 10 })).toBe('type')
    expect(petImageProblem({ type: 'image/png', size: 2 * 1024 * 1024 + 1 })).toBe('size')
    expect(petImageKind(new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))).toBe('image/png')
    expect(petImageKind(new Uint8Array([0xff, 0xd8, 0xff, 0xe0]))).toBe('image/jpeg')
    expect(petImageKind(new Uint8Array([0x47, 0x49, 0x46, 0x38]))).toBeNull()
  })
})

describe('Server-Form', () => {
  it('Wunsch: Tierart, Beschreibung, Farben', () => {
    expect(checkPetRequest({ species: 'Fuchs', description: 'Mit Schal', colors: '' })).toBeNull()
    expect(checkPetRequest({ species: '  ', description: 'x', colors: '' })).toBe('species')
    expect(checkPetRequest({ species: 'F'.repeat(61), description: 'x', colors: '' })).toBe('species')
    expect(checkPetRequest({ species: 'Fuchs', description: ' ', colors: '' })).toBe('description')
    expect(checkPetRequest({ species: 'Fuchs', description: 'x'.repeat(1001), colors: '' })).toBe('description')
    expect(checkPetRequest({ species: 'Fuchs', description: 'x', colors: 'c'.repeat(201) })).toBe('colors')
  })

  it('canRequest vom Server hat Vorrang, resetsAt auch', () => {
    expect(canRequestPet({ pet: null, request: null, canRequest: false })).toBe(false)
    expect(canRequestPet({ pet: null, request: null, canRequest: true })).toBe(true)
    expect(petLimitResetsAt('2026-10', '2026-11-01T00:00:00.000Z')!.toISOString()).toBe('2026-11-01T00:00:00.000Z')
  })

  it('Haustier-Ereignisse sind bekannte creator_updated-Arten', () => {
    for (const kind of ['pet_assigned', 'pet_removed', 'pet_extras_changed', 'pet_request_answered']) {
      expect(creatorEventSchemas[0].parse({ type: 'creator_updated', kind, reason: 'Fertig', tier: null, milestone: null, helper: null }).kind).toBe(kind)
    }
  })
})

describe('Reiter', () => {
  it('Helfer mit „Codes“ sehen den Haustier-Reiter, ohne nicht', () => {
    expect(visibleTabs(null)).toContain('pet')
    expect(visibleTabs({ helper: true, rights: ['codes'] })).toEqual(['codes', 'pet'])
    expect(visibleTabs({ helper: true, rights: ['stats'] })).not.toContain('pet')
  })
})
