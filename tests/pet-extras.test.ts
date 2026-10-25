import { describe, expect, it } from 'vitest'
import { creatorPetStateSchema } from '../app/utils/creator'
import { DRIVERS, LIMITS, MAX_PET_GROUP, PET_GRAB_MS, PET_STATES, petGroup, petGroupSlots, petScale, petSlots, petStateAvailable, resolveDriver } from '../app/utils/cosmetic-v2/format'
import { trsHeadCosmeticSchema } from '../app/utils/trs'

// Gefolge eines Creators (Vertrag pixelcst-penguins): Haupt-Pet x count + je 1 x jedes Extra, eigene
// Größe je Pet, gesamt höchstens 8. Nur reine Regeln und Schemas; die Zeichnung selbst prüft der Screenshot.

describe('Größe der Begleiter', () => {
  it('nimmt 0,25 bis 3, sonst 1', () => {
    expect(petScale(1.25)).toBe(1.25)
    expect(petScale(0.25)).toBe(0.25)
    expect(petScale(3)).toBe(3)
    for (const bad of [undefined, null, '1.25', NaN, Infinity, 0, -1, 0.1, 3.5, 99]) expect(petScale(bad)).toBe(1)
    expect(petScale(1.12345)).toBe(1.123)
  })
})

describe('Gruppe aus Haupt-Pet und Gefolge', () => {
  it('zeichnet count x Haupt-Pet, dann je 1 x Extra', () => {
    const g = petGroup(2, 1, [1.25, 1, 1])
    expect(g.map((e) => e.kind)).toEqual(['main', 'main', 'extra', 'extra', 'extra'])
    expect(g.filter((e) => e.kind === 'extra').map((e) => [e.extra, e.scale])).toEqual([[0, 1.25], [1, 1], [2, 1]])
  })

  it('PixelCST: 1 Haupt-Pet und drei Extras, Kaiserpinguin 1,25', () => {
    const g = petGroup(1, 1, [1.25, 1, 1])
    expect(g).toHaveLength(4)
    expect(g[1]).toEqual({ kind: 'extra', extra: 0, scale: 1.25 })
  })

  it('ohne Gefolge wie bisher; ungültige Zahl = 1', () => {
    expect(petGroup(3, undefined, [])).toHaveLength(3)
    expect(petGroup('x', 1, [])).toHaveLength(1)
    expect(petGroup(9, 1, [])).toHaveLength(4)
  })

  it('höchstens 4 Extras und höchstens 8 insgesamt', () => {
    expect(petGroup(4, 1, [1, 1, 1, 1, 1, 1])).toHaveLength(MAX_PET_GROUP)
    expect(petGroup(1, 1, [1, 1, 1, 1, 1, 1])).toHaveLength(5)
  })

  it('Plätze: bis 4 wie bisher, weitere spiegelbildlich, ohne doppelte Plätze', () => {
    expect(petGroupSlots(4)).toEqual(petSlots(4))
    const eight = petGroupSlots(8)
    expect(eight).toHaveLength(8)
    expect(petGroupSlots(20)).toHaveLength(8)
    expect(petGroupSlots(0)).toEqual([])
    expect(eight[4]!.x).toBeCloseTo(-eight[0]!.x)
    expect(eight[4]!.yaw).toBeCloseTo(-eight[0]!.yaw)
    expect(new Set(eight.map((s) => `${s.x},${s.z}`)).size).toBe(8)
  })

  it('breite Aufstellung fürs Gefolge: erster Platz wie beim einzelnen Begleiter, Paare links/rechts', () => {
    const wide = petGroupSlots(8, true)
    expect(wide).toHaveLength(8)
    expect(wide[0]).toEqual(petSlots(1)[0])
    for (let i = 0; i < 8; i += 2) {
      expect(wide[i + 1]!.x).toBeCloseTo(-wide[i]!.x)
      expect(wide[i + 1]!.z).toBeCloseTo(wide[i]!.z)
    }
    expect(new Set(wide.map((s) => `${s.x},${s.z}`)).size).toBe(8)
    expect(petGroupSlots(3, true)).toHaveLength(3)
  })
})

describe('Neue Begleiter-Zustände (Anhang E)', () => {
  const anim = (driver: string) => ({ id: driver, driver, lengthMs: 400, tracks: [] })
  const heist = (drivers: string[]) => ({ slot: 'pet', animations: drivers.map(anim) }) as never
  it('Grenze 12 Animationen und die neuen Treiber', () => {
    expect(LIMITS.animations).toBe(12)
    for (const d of ['sneak', 'grab', 'flee', 'handsup']) expect(DRIVERS).toContain(d)
    expect(PET_STATES).toEqual(expect.arrayContaining(['sneak', 'grab', 'flee', 'handsup']))
    expect(PET_GRAB_MS).toBe(2400)
  })

  it('Rückfall: sneak/flee → walk, grab/handsup → idle', () => {
    const plain = heist(['idle', 'walk'])
    expect(resolveDriver(plain, 'sneak')).toBe('walk')
    expect(resolveDriver(plain, 'flee')).toBe('walk')
    expect(resolveDriver(plain, 'grab')).toBe('idle')
    expect(resolveDriver(plain, 'handsup')).toBe('idle')
    const full = heist(['idle', 'walk', 'sneak', 'grab', 'flee', 'handsup'])
    for (const s of ['sneak', 'grab', 'flee', 'handsup'] as const) expect(resolveDriver(full, s)).toBe(s)
    // Flügel kennen den Rückfall auf walk nicht.
    expect(resolveDriver({ slot: 'wings', animations: [anim('walk')] } as never, 'sneak')).toBe('idle')
  })

  it('Knöpfe: Grundzustände immer, die neuen nur mit Animation', () => {
    const plain = heist(['idle'])
    expect(PET_STATES.filter((s) => petStateAvailable(plain, s))).toEqual(['idle', 'walk', 'slide', 'swim', 'air'])
    const full = heist(['idle', 'grab', 'handsup'])
    expect(PET_STATES.filter((s) => petStateAvailable(full, s))).toEqual(['idle', 'walk', 'slide', 'swim', 'air', 'grab', 'handsup'])
  })
})

const base = {
  id: 'penguin', name: 'Pinguin', format: 2, slot: 'pet', template: null, unlock: 'code', owned: true, equipped: true, preview: true,
  hash: 'ab12', card: null, cardNight: null, frames: 1, glowFrames: 0,
}

describe('Kosmetik-Eintrag mit Gefolge', () => {
  it('liest Größe und Gefolge', () => {
    const c = trsHeadCosmeticSchema.parse({
      ...base, scale: 1.25,
      extras: [{ id: 'penguin_emperor', name: 'Kaiserpinguin', scale: 1.25, hash: 'e1', preview: true }, { id: 'penguin_beanie', name: 'Mütze' }],
    })
    expect(c.scale).toBe(1.25)
    expect(c.extras.map((e) => [e.id, e.scale, e.preview, e.hash])).toEqual([['penguin_emperor', 1.25, true, 'e1'], ['penguin_beanie', 1, false, null]])
  })

  it('ältere Kerne ohne die Felder: Größe 1, kein Gefolge; Unsinn fällt zurück', () => {
    const c = trsHeadCosmeticSchema.parse(base)
    expect([c.scale, c.extras]).toEqual([1, []])
    const bad = trsHeadCosmeticSchema.parse({ ...base, scale: 99, extras: 'x' })
    expect([bad.scale, bad.extras]).toEqual([1, []])
  })
})

describe('Haustier-Stand mit Gefolge', () => {
  const state = { pet: null, limit: null, request: null, canRequest: null }
  it('liest Karten, Größe und Schalter', () => {
    const s = creatorPetStateSchema.parse({
      ...state, pet: { cosmeticId: 'penguin', name: 'Pinguin', card: null, count: 1, available: true },
      extras: [{ cosmeticId: 'penguin_emperor', name: 'Kaiserpinguin', card: null, scale: 1.25 }, { cosmeticId: 'penguin_shades', name: 'Sonnenbrille', card: null }],
      escortVisible: false,
    })
    expect(s.extras.map((e) => [e.cosmeticId, e.scale])).toEqual([['penguin_emperor', 1.25], ['penguin_shades', 1]])
    expect(s.escortVisible).toBe(false)
  })

  it('ohne Felder: kein Gefolge, Schalter an', () => {
    const s = creatorPetStateSchema.parse(state)
    expect([s.extras, s.escortVisible]).toEqual([[], true])
  })
})
