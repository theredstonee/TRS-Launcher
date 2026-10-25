// Winter 2026 (Anhang D des Kosmetik-Formats): Optionen (Bart an/aus), Begleiter am Boden (slot pet,
// attach ground) mit den Zuständen idle/walk/slide/swim/air. Nur synthetische Platzhalter-Modelle –
// die echten Winter-Teile sind proprietär und gehören nicht ins Repo.
import { describe, expect, it } from 'vitest'
import * as THREE from 'three'
import {
  PET_STATES,
  createStateMixer,
  hiddenBones,
  modelOptions,
  optionName,
  petCount,
  petSlots,
  petTimeOffset,
  resolveDriver,
  resolveOptions,
  stateAnimations,
  validateModel,
  type CosmeticModel,
} from '../app/utils/cosmetic-v2/format'
import { createCosmetic } from '../app/utils/cosmetic-v2/view'
import { i18n, setLocale } from '../app/utils/i18n'
import { optionToggles, petCountText, toggleOption } from '../app/utils/trsCosmetics'
import { trsHeadCosmeticSchema, trsRedeemSchema } from '../app/utils/trs'

/** Platzhalter-Mütze: Mütze → Spitze → Bommel, dazu ein abschaltbarer „Bart“ mit Kind-Knochen. */
function cap(): CosmeticModel {
  return {
    format: 2,
    id: 'test_cap',
    name: 'Testmütze',
    slot: 'hat',
    attach: 'head',
    options: [{ id: 'beard', bones: ['beard'], default: true, name: { de: 'Bart', en: 'Beard', es: 'Barba' } }],
    texture: { file: 'texture.png', width: 16, height: 8, scale: 1 },
    bones: [
      { id: 'hat', pivot: [0, 8, 0] },
      { id: 'tip', parent: 'hat', pivot: [0, 12, 0] },
      { id: 'beard', pivot: [0, 0, 4] },
      { id: 'curl', parent: 'beard', pivot: [0, -2, 4] },
    ],
    cubes: [
      { bone: 'hat', from: [-4, 8, -4], to: [4, 10, 4], faces: { south: { uv: [0, 0, 8, 2] } } },
      { bone: 'tip', from: [-1, 10, -1], to: [1, 12, 1], faces: { south: { uv: [8, 0, 10, 2] } } },
      { bone: 'beard', from: [-3, -2, 4], to: [3, 2, 5], faces: { south: { uv: [0, 2, 6, 6] } } },
      { bone: 'curl', from: [-1, -3, 4], to: [1, -2, 5], faces: { south: { uv: [6, 2, 8, 3] } } },
    ],
    halos: [{ bone: 'curl', pos: [0, -3, 5], size: 2, color: '#ffffff' }],
  }
}

/** Platzhalter-Begleiter am Boden (Würfel-Tier) mit allen fünf Zuständen außer swim. */
function pet(): CosmeticModel {
  const key = (y: number) => ({ id: '', lengthMs: 400, tracks: [{ bone: 'body', channel: 'rotation' as const, keys: [{ t: 0, v: [y, 0, 0] as [number, number, number] }] }] })
  return {
    format: 2,
    id: 'test_pet',
    name: 'Testtier',
    slot: 'pet',
    attach: 'ground',
    texture: { file: 'texture.png', width: 16, height: 8, scale: 1 },
    bones: [
      { id: 'root', pivot: [0, 0, 0] },
      { id: 'body', parent: 'root', pivot: [0, 4, 0] },
    ],
    cubes: [{ bone: 'body', from: [-3, 0, -3], to: [3, 8, 3], faces: { south: { uv: [0, 0, 6, 8] } } }],
    animations: [
      { ...key(0), id: 'idle', driver: 'idle' },
      { ...key(10), id: 'walk', driver: 'walk' },
      { ...key(80), id: 'slide', driver: 'slide' },
      { ...key(-20), id: 'air', driver: 'air' },
    ],
  }
}

const errorsAt = (m: unknown, prefix: string) => validateModel(m).errors.filter((e) => e.startsWith(prefix))

describe('Winter – Prüfung (Anhang D)', () => {
  it('nimmt Optionen, slot pet mit attach ground und die neuen Treiber an', () => {
    expect(validateModel(cap()).errors).toEqual([])
    const p = pet()
    p.animations!.push({ id: 'swim', driver: 'swim', lengthMs: 400, tracks: [{ bone: 'body', channel: 'position', keys: [{ t: 0, v: [0, 1, 0] }] }] })
    expect(validateModel(p).errors).toEqual([])
  })

  it('Begleiter am Boden brauchen attach ground, Hüte bleiben am Kopf', () => {
    const p = pet()
    p.attach = 'head'
    expect(errorsAt(p, 'attach')).toHaveLength(1)
    const h = cap() as unknown as Record<string, unknown>
    h.attach = 'ground'
    expect(errorsAt(h, 'attach')).toHaveLength(1)
    expect(errorsAt({ ...pet(), slot: 'pets' }, 'slot')).toHaveLength(1)
  })

  it('findet kaputte Optionen', () => {
    const bad = (options: unknown) => errorsAt({ ...cap(), options }, 'options')
    expect(bad([{ id: 'Beard', bones: ['beard'], default: true }])).toHaveLength(1)
    expect(bad([{ id: 'beard', bones: ['nope'], default: true }])).toHaveLength(1)
    expect(bad([{ id: 'beard', bones: [], default: true }])).toHaveLength(1)
    expect(bad([{ id: 'beard', bones: ['beard'], default: 'yes' }])).toHaveLength(1)
    expect(bad([{ id: 'beard', bones: ['beard'], default: true, name: { de: 'x'.repeat(41) } }])).toHaveLength(1)
    expect(bad([{ id: 'a', bones: ['beard'], default: true }, { id: 'a', bones: ['hat'], default: false }])).toHaveLength(1)
    const five = ['a', 'b', 'c', 'd', 'e'].map((id) => ({ id, bones: ['beard'], default: true }))
    expect(bad(five)).toHaveLength(1)
    expect(bad('beard')).toHaveLength(1)
  })
})

describe('Winter – Optionen', () => {
  it('Standardwerte, Wahl des Spielers und unbekannte Einträge', () => {
    const m = cap()
    expect(resolveOptions(m, null)).toEqual({ beard: true })
    expect(resolveOptions(m, { beard: false, nope: true })).toEqual({ beard: false })
    expect(modelOptions({ options: [{ id: 'x', bones: ['a'], default: 1 }] })).toEqual([])
    expect(modelOptions(null)).toEqual([])
  })

  it('Option aus blendet die Knochen samt Kind-Knochen aus', () => {
    const m = cap()
    expect([...hiddenBones(m, null)]).toEqual([])
    expect([...hiddenBones(m, { beard: false })].sort()).toEqual(['beard', 'curl'])
    // Standard „aus“ greift ohne Wahl.
    m.options![0]!.default = false
    expect(hiddenBones(m, {}).has('curl')).toBe(true)
    expect(hiddenBones(m, { beard: true }).size).toBe(0)
  })

  it('Renderer: unsichtbare Knochen-Gruppen nehmen Würfel, Leucht-Schicht und Höfe mit', () => {
    const canvas = { width: 16, height: 8 } as unknown as HTMLCanvasElement
    const origDoc = globalThis.document
    // Höfe brauchen eine kleine Leinwand für ihre Verlaufs-Textur – hier eine Attrappe.
    globalThis.document = {
      createElement: () => ({ width: 0, height: 0, getContext: () => ({ createImageData: (w: number, h: number) => ({ data: new Uint8ClampedArray(w * h * 4) }), putImageData() {} }) }),
    } as unknown as Document
    try {
      const inst = createCosmetic(cap(), { texture: canvas })
      const byName = (n: string) => inst.root.getObjectByName(n)!
      expect(byName('beard').visible).toBe(true)
      inst.setOptions({ beard: false })
      expect(byName('beard').visible).toBe(false)
      expect(byName('hat').visible).toBe(true)
      // Kind-Knochen hängt in three.js unter dem Bart: unsichtbar über die Eltern-Kette.
      let shown = true
      byName('curl').traverseAncestors((o) => (shown &&= o.visible))
      expect(shown).toBe(false)
      const sprites: THREE.Object3D[] = []
      inst.root.traverseVisible((o) => (o as THREE.Sprite).isSprite && sprites.push(o))
      expect(sprites).toHaveLength(0)
      inst.setOptions(null)
      expect(byName('beard').visible).toBe(true)
      inst.dispose()
    } finally {
      globalThis.document = origDoc
    }
  })

  it('Schalter in der Sprache des Launchers, Umschalten schickt alle Werte', () => {
    const m = cap()
    expect(optionName(m.options![0]!, 'es')).toBe('Barba')
    expect(optionName(m.options![0]!, 'pt-BR')).toBe('Beard')
    expect(optionName({ id: 'x', bones: [], default: true }, 'de')).toBe('x')
    expect(optionToggles(m, { beard: false }, 'de')).toEqual([{ option: m.options![0], label: 'Bart', on: false }])
    expect(toggleOption(m, null, 'beard')).toEqual({ beard: false })
    expect(toggleOption(m, { beard: false }, 'beard')).toEqual({ beard: true })
    expect(toggleOption(m, null, 'nope')).toEqual({ beard: true })
  })
})

describe('Winter – Begleiter am Boden', () => {
  it('spielt idle/walk/slide/swim/air mit Rückfall auf idle', () => {
    const m = pet()
    expect(PET_STATES).toEqual(['idle', 'walk', 'slide', 'swim', 'air', 'sneak', 'grab', 'flee', 'handsup'])
    expect(resolveDriver(m, 'slide')).toBe('slide')
    expect(resolveDriver(m, 'swim')).toBe('idle')
    expect(stateAnimations(m, 0, 'slide').get('body')!.rotation[0]).toBe(80)
    expect(stateAnimations(m, 0, 'swim').get('body')!.rotation[0]).toBe(0)
    const mix = createStateMixer()
    mix.pose(m, 0, 'walk')
    mix.pose(m, 1000, 'slide')
    expect(mix.pose(m, 1125, 'slide').get('body')!.rotation[0]).toBeCloseTo(45)
    expect(mix.pose(m, 1300, 'slide').get('body')!.rotation[0]).toBe(80)
  })

  it('Anzahl 1…4, Plätze neben dem Spieler, eigener Zeitversatz', () => {
    expect([petCount(undefined), petCount(0), petCount(2.7), petCount(9), petCount('3')]).toEqual([1, 1, 2, 4, 1])
    const spots = petSlots(4)
    expect(spots).toHaveLength(4)
    expect(spots[0]).toEqual({ x: 13, y: -16, z: 7, yaw: 0.35 })
    // Alle stehen auf dem Boden (Füße von skinview3d) und links vom Spieler, nicht in ihm.
    for (const s of spots) expect([s.y, s.x - 8 > 4]).toEqual([-16, true])
    expect(new Set(spots.map((s) => `${s.x}:${s.z}`)).size).toBe(4)
    expect(petSlots(null)).toHaveLength(1)
    const uuid = '069a79f4-44e9-4726-a5be-fca90e38aaf5'
    expect(petTimeOffset(uuid, 0)).toBe(petTimeOffset(uuid.replace(/-/g, '').toUpperCase(), 0))
    const offsets = [0, 1, 2, 3].map((i) => petTimeOffset(uuid, i))
    expect(new Set(offsets).size).toBe(4)
    for (const o of offsets) expect(o >= 0 && o < 60000 && Number.isInteger(o)).toBe(true)
  })
})

describe('Winter – Texte und Kern-Daten', () => {
  it('„2 Pinguine – Creator-Stufe“', async () => {
    await setLocale('de')
    const penguin = { id: 'penguin', name: 'Pinguin', count: 2, creator: true }
    expect(petCountText(penguin)).toBe('2 Pinguine – Creator-Stufe')
    expect(petCountText({ ...penguin, count: 1, creator: false })).toBe('1 Pinguin')
    expect(petCountText({ ...penguin, count: null, creator: false })).toBe('1 Pinguin')
    expect(petCountText({ id: 'seal', name: 'Robbe', count: 3, creator: true })).toBe('3 × Robbe – Creator-Stufe')
    await setLocale('en')
    expect(petCountText(penguin)).toBe('2 penguins – creator tier')
    expect(i18n.global.locale.value).toBe('en')
  })

  it('ältere Kerne ohne count/options/slot bleiben gültig', () => {
    const base = {
      id: 'penguin', name: 'Pinguin', format: 2, slot: 'pet', template: null, unlock: 'code', owned: true, equipped: true,
      preview: true, hash: 'abc123', card: null, cardNight: null, frames: 1, glowFrames: 0,
    }
    const old = trsHeadCosmeticSchema.parse(base)
    expect([old.slot, old.count, old.creator, old.options]).toEqual(['pet', null, false, {}])
    const now = trsHeadCosmeticSchema.parse({ ...base, count: 3, creator: true, options: { beard: false } })
    expect([now.count, now.creator, now.options]).toEqual([3, true, { beard: false }])
    expect(trsHeadCosmeticSchema.parse({ ...base, count: 9, options: { 'Bad-Key': true } })).toMatchObject({ count: null, options: {} })
    const redeem = { kind: 'cosmetic', capeId: null, cosmeticId: 'penguin', name: 'Pinguin', alreadyOwned: false, wearableHat: true }
    expect(trsRedeemSchema.parse(redeem).slot).toBeNull()
    expect(trsRedeemSchema.parse({ ...redeem, slot: 'pet' }).slot).toBe('pet')
  })
})
