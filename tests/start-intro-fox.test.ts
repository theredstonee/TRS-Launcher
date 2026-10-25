import { describe, expect, it } from 'vitest'
import { introDesignById, pickIntroDesign } from '../app/utils/intro/designs'
import { applyXf, DEN, faceShade, familyAt, foxModel, foxTexel, KIT_SPOTS, POUNCE, pounceAt, SLEEPER, type FoxPose, type Part, type V3 } from '../app/utils/intro/fox'
import { camAt, familyCues, fireGlow, FOX_CENTER_PX, FOXDEN_BEAT, FOXDEN_CUES, gustAt, LEAP_CAM, spruceHalf, SUN, sunAt } from '../app/utils/intro/foxden'
import { FOX_SCALE, planFoxden } from '../app/utils/intro/sfx'
import { INTRO_CUES, INTRO_LENGTHS } from '../app/utils/intro/timeline'

const DURATIONS = { short: 4, medium: 7, long: 10 } as const
/** Liegt t auf dem Raster impact − k · Schlag (k ganzzahlig oder halb)? */
const onGrid = (t: number, impact: number) => {
  const k = (impact - t) / FOXDEN_BEAT
  return Math.abs(k * 2 - Math.round(k * 2)) < 0.01
}

describe('Start-Animation Fuchsbau: Registry', () => {
  it('Design „fox“ ist wählbar, Events haben weiter Vorrang', () => {
    expect(pickIntroDesign({ design: 'fox' }).id).toBe('fox')
    expect(pickIntroDesign({ events: ['halloween'], design: 'fox' }).id).toBe('halloween')
    expect(introDesignById('fox').scene).toBe('foxden')
    expect(introDesignById('fox').audio).toEqual({ mood: 'fox', jingleHit: 9.72 })
  })
})

describe('Start-Animation Fuchsbau: Zeitplan', () => {
  it('Grundschnitt, Schlag zur selben Zeit wie die anderen Sets', () => {
    for (const len of INTRO_LENGTHS) {
      const c = FOXDEN_CUES[len]
      expect(c.duration).toBe(DURATIONS[len])
      expect(c.impact).toBe(INTRO_CUES[len].impact)
      expect(c.fadeIn).toBeGreaterThan(0)
      expect(c.gather).toBeLessThan(c.hush)
      expect(c.hush).toBeLessThan(c.impact)
      expect(c.impact).toBeLessThan(c.logo)
      expect(c.logo).toBeLessThanOrEqual(c.wordmark)
      expect(c.wordmark).toBeLessThan(c.fadeOut)
      expect(c.fadeOut).toBeLessThan(c.duration)
    }
  })

  it('alle Ereignisse liegen auf dem Schlagraster des Jingles', () => {
    for (const len of INTRO_LENGTHS) {
      const c = FOXDEN_CUES[len]
      const events = [c.hush, c.gather, ...c.pounce.filter((t) => t > 0), ...c.kits.filter((t) => t >= 0)]
      if (c.fire >= 0) events.push(c.fire)
      if (c.lantern >= 0) events.push(c.lantern)
      if (c.berries) events.push(c.berries[1])
      for (const t of events) expect(onGrid(t, c.impact), `${len} ${t}`).toBe(true)
    }
    // Der Absprung liegt in der langen Fassung auf dem großen Einsatz des Jingles (impact − 4 Schläge)
    const L = FOXDEN_CUES.long
    expect(L.pounce[1]).toBeCloseTo(L.impact - 4 * FOXDEN_BEAT, 3)
  })

  it('Reihenfolge: Feuer → Junge → Beeren → Pounce → Sammeln → Schlag', () => {
    for (const len of INTRO_LENGTHS) {
      const c = FOXDEN_CUES[len]
      const [p0, p1, p2] = c.pounce
      expect(p0).toBeLessThanOrEqual(p1)
      expect(p1).toBeLessThan(p2)
      expect(p2).toBeLessThanOrEqual(c.gather + 1e-9)
      for (const k of c.kits) if (k >= 0) expect(k).toBeGreaterThan(c.fire)
      if (c.berries) {
        expect(c.berries[0]).toBeLessThan(c.berries[1])
        expect(c.berries[1]).toBeLessThan(p1)
      }
    }
    expect(FOXDEN_CUES.short.kits.every((k) => k < 0)).toBe(true)
    expect(FOXDEN_CUES.long.kits.every((k) => k >= 0)).toBe(true)
  })

  it('Kamera: Stützpunkte zeitlich sortiert, Fahrt stetig, im Scheitel genau LEAP_CAM', () => {
    for (const len of INTRO_LENGTHS) {
      const c = FOXDEN_CUES[len]
      for (let i = 1; i < c.cam.length; i++) expect(c.cam[i]![0]).toBeGreaterThan(c.cam[i - 1]![0])
      const apex = (c.pounce[1] + c.pounce[2]) / 2
      const [x, y, z] = camAt(c.cam, apex)
      expect(x).toBeCloseTo(LEAP_CAM[0], 6)
      expect(y).toBeCloseTo(LEAP_CAM[1], 6)
      expect(z).toBeCloseTo(LEAP_CAM[2], 6)
      // keine Sprünge: zwischen zwei Bildern (60 Hz) bewegt sich die Kamera wenig
      let prev = camAt(c.cam, 0)
      for (let t = 1 / 60; t < c.duration; t += 1 / 60) {
        const cur = camAt(c.cam, t)
        expect(Math.abs(cur[0] - prev[0])).toBeLessThan(6)
        expect(Math.abs(cur[1] - prev[1])).toBeLessThan(6)
        prev = cur
      }
    }
  })

  it('im Scheitel des Pounce steht die Sonne genau hinter dem Fuchs', () => {
    for (const len of INTRO_LENGTHS) {
      const c = FOXDEN_CUES[len]
      const apex = (c.pounce[1] + c.pounce[2]) / 2
      const [cx, cy] = camAt(c.cam, apex)
      const sun = sunAt(c, apex)
      // Bildschirmlage relativ zur Mitte: Welt − Kamera · Parallaxe
      const sunRel = { x: sun.x - cx * SUN.par, y: sun.y - cy * SUN.par }
      const fox = pounceAt(0.5)
      const foxRel = { x: fox.x - cx, y: fox.y - FOX_CENTER_PX - cy }
      expect(sunRel.x).toBeCloseTo(foxRel.x, 3)
      expect(sunRel.y).toBeCloseTo(foxRel.y, 3)
    }
  })
})

describe('Start-Animation Fuchsbau: Fuchsfamilie', () => {
  it('Pounce: vom Absprung über den Scheitel in den Busch, erst Nase hoch, dann kopfüber', () => {
    const a = pounceAt(0)
    const m = pounceAt(0.5)
    const b = pounceAt(1)
    expect(a.x).toBeCloseTo(POUNCE.from.x)
    expect(m.x).toBeCloseTo(POUNCE.apex.x)
    expect(m.y).toBeCloseTo(POUNCE.apex.y)
    expect(b.x).toBeCloseTo(POUNCE.to.x)
    expect(pounceAt(0.2).rot).toBeLessThan(0)
    expect(pounceAt(0.85).rot).toBeGreaterThan(0.5)
  })

  it('einer schläft immer eingerollt oben auf dem Bau', () => {
    const c = familyCues(FOXDEN_CUES.long)
    for (let t = 0; t < 10; t += 0.25) {
      const s = familyAt(c, t).find((f) => f.id === 'sleeper')!
      expect(s.look.pose).toBe('sleep')
      expect(s.x).toBe(SLEEPER.x)
      expect(s.y).toBe(SLEEPER.y)
    }
  })

  it('Junge purzeln nacheinander aus dem Bau und sitzen vor dem Schlag alle auf ihren Plätzen', () => {
    const cues = FOXDEN_CUES.long
    const c = familyCues(cues)
    cues.kits.forEach((te, i) => {
      expect(familyAt(c, te - 0.4).some((f) => f.id === `kit${i}`)).toBe(false)
      const out = familyAt(c, te + 0.1).find((f) => f.id === `kit${i}`)!
      expect(out.look.pose).toBe('tumble')
      expect(Math.abs(out.x - DEN.x)).toBeLessThan(Math.abs(KIT_SPOTS[i]! - DEN.x) + 1)
    })
    const before = familyAt(c, cues.impact - 0.1).filter((f) => f.id.startsWith('kit'))
    expect(before).toHaveLength(3)
    for (const k of before) expect(k.look.pose).toBe('sit')
  })

  it('der Jäger trägt Süßbeeren im Maul, bis er sie ablegt, springt dann und schaut mit Beere aus dem Busch', () => {
    const cues = FOXDEN_CUES.long
    const c = familyCues(cues)
    const hunter = (t: number) => familyAt(c, t).find((f) => f.id === 'hunter')
    expect(hunter(cues.berries![1] - 0.3)!.look.berries).toBe(true)
    expect(hunter(cues.berries![1] - 0.3)!.look.pose).toBe('walk')
    expect(hunter(cues.berries![1] + 0.3)!.look.berries).toBe(false)
    expect(hunter(cues.pounce[0] + 0.1)!.look.pose).toBe('crouch')
    const air = hunter((cues.pounce[1] + cues.pounce[2]) / 2)!
    expect(air.airborne).toBe(true)
    expect(air.look.pose).toBe('leap')
    // Gegenlicht nur vor der Sonne, beim Absprung und Eintauchen farbig
    expect(hunter(cues.pounce[1] + (cues.pounce[2] - cues.pounce[1]) * 0.1)!.airborne).toBe(false)
    expect(hunter(cues.pounce[1] + (cues.pounce[2] - cues.pounce[1]) * 0.1)!.look.pose).toBe('leap')
    expect(hunter(cues.pounce[2] + 0.1)).toBeUndefined()
    expect(hunter(cues.impact)!.look.pose).toBe('peek')
    expect(hunter(cues.impact)!.look.berries).toBe(true)
  })

  it('Box-Modell wie in Minecraft: Kopf mit Schnauze und zwei Ohren, Körper, vier Beine, Schwanz', () => {
    const count = (ps: Part[], k: Part['kind']) => ps.filter((p) => p.kind === k).length
    const stand = foxModel({ pose: 'stand', kit: false, frame: 0, berries: false })
    expect(count(stand, 'head')).toBe(1)
    expect(count(stand, 'snout')).toBe(1)
    expect(count(stand, 'ear')).toBe(2)
    expect(count(stand, 'body')).toBe(1)
    expect(count(stand, 'leg')).toBe(4)
    expect(count(stand, 'tail')).toBe(1)
    const head = stand.find((p) => p.kind === 'head')!
    const body = stand.find((p) => p.kind === 'body')!
    expect(head.size).toEqual([6, 6, 8])
    expect(body.size).toEqual([11, 6, 6])
    expect(stand.find((p) => p.kind === 'snout')!.size).toEqual([3, 2, 4])
    expect(stand.find((p) => p.kind === 'tail')!.size).toEqual([9, 5, 4])
    for (const p of stand.filter((q) => q.kind === 'leg')) expect(p.size).toEqual([2, 6, 2])
    // Beere quer im Maul: breiter als die Schnauze
    const carry = foxModel({ pose: 'walk', kit: false, frame: 1, berries: true })
    expect(carry.find((p) => p.kind === 'berry')!.size[2]).toBeGreaterThan(4)
    // alle Posen: positive Maße, nichts unter dem Boden
    const poses: FoxPose[] = ['stand', 'walk', 'sit', 'sleep', 'crouch', 'leap', 'peek', 'sniff', 'tumble']
    for (const kit of [false, true]) {
      for (const pose of poses) {
        for (const p of foxModel({ pose, kit, frame: 1, berries: true, pitch: 0 })) {
          for (const d of p.size) expect(d).toBeGreaterThan(0)
          if (pose !== 'tumble') expect(applyXf(p.min, p.xf)[1]).toBeGreaterThan(-6)
        }
      }
    }
  })

  it('Junge: großer Kopf, kleiner Körper (Minecraft-Babyfuchs)', () => {
    const ratio = (kit: boolean) => {
      const ps = foxModel({ pose: 'stand', kit, frame: 0, berries: false })
      return ps.find((p) => p.kind === 'head')!.size[0] / ps.find((p) => p.kind === 'body')!.size[0]
    }
    expect(ratio(true)).toBeGreaterThan(ratio(false) * 1.3)
  })

  it('Pounce: Vorderbeine vorgestreckt; Schlafen: Schwanz liegt vorn über der Schnauze, Augen zu', () => {
    const leap = foxModel({ pose: 'leap', kit: false, frame: 0, berries: false, pitch: 0 })
    const bodyFront = 5.5
    const frontLegs = leap.filter((p) => p.kind === 'leg' && p.min[0] > 0)
    for (const l of frontLegs) {
      const foot = applyXf([l.min[0] + 1, l.min[1], l.min[2] + 1], l.xf)
      expect(foot[0]).toBeGreaterThan(bodyFront)
    }
    const sleep = foxModel({ pose: 'sleep', kit: false, frame: 0, berries: false })
    const tail = sleep.find((p) => p.kind === 'tail')!
    const snout = sleep.find((p) => p.kind === 'snout')!
    expect(tail.tipFront).toBe(true)
    expect(sleep.find((p) => p.kind === 'head')!.closed).toBe(true)
    expect(sleep.some((p) => p.kind === 'leg')).toBe(false)
    const tailFront = applyXf([tail.min[0] + tail.size[0], tail.min[1] + tail.size[1], tail.min[2]], tail.xf)
    const snoutBottom = applyXf(snout.min, snout.xf)
    expect(tailFront[1]).toBeGreaterThan(snoutBottom[1])
  })

  it('Texturen: weiße Schwanzspitze, dunkle Socken, Augen mit Glanzpixel, schwarze Nase', () => {
    const stand = foxModel({ pose: 'stand', kit: false, frame: 0, berries: false })
    const tail = stand.find((p) => p.kind === 'tail')!
    expect(foxTexel(tail, 'pz', 0, 2, 9, 5)).toBe('w')
    expect(foxTexel(tail, 'pz', 8, 2, 9, 5)).not.toBe('w')
    const leg = stand.find((p) => p.kind === 'leg')!
    expect(foxTexel(leg, 'pz', 0, 5, 2, 6)).toMatch(/^[dD]$/)
    expect(foxTexel(leg, 'pz', 0, 1, 2, 6)).toBe('o')
    const head = stand.find((p) => p.kind === 'head')!
    const face: string[] = []
    for (let v = 0; v < 6; v++) for (let u = 0; u < 8; u++) face.push(foxTexel(head, 'px', u, v, 8, 6))
    expect(face.filter((c) => c === 'g')).toHaveLength(2)
    expect(face.filter((c) => c === 'e').length).toBeGreaterThanOrEqual(4)
    const snout = stand.find((p) => p.kind === 'snout')!
    expect([0, 1, 2, 3].map((u) => foxTexel(snout, 'px', u, 0, 4, 2))).toContain('n')
  })

  it('Schattierung je Fläche: oben hell, Seite mittel, unten dunkel', () => {
    const up: V3 = [0, 1, 0]
    const side: V3 = [0, 0, 1]
    const down: V3 = [0, -1, 0]
    expect(faceShade(up)).toBeGreaterThan(faceShade(side))
    expect(faceShade(side)).toBeGreaterThan(faceShade(down))
  })
})

describe('Start-Animation Fuchsbau: Licht und Bild', () => {
  it('ruhige Fassung: Feuer flammt ohne Aufflackern auf und bleibt dann konstant', () => {
    let prev = 0
    for (let t = 1; t < 4; t += 0.01) {
      const g = fireGlow(1, t, 0, true)
      expect(g).toBeGreaterThanOrEqual(prev - 1e-9)
      expect(g).toBeLessThanOrEqual(1)
      prev = g
    }
    expect(fireGlow(1, 3, 0, true)).toBe(1)
    expect(fireGlow(2, 1.5, 0, true)).toBe(0)
    expect(fireGlow(-1, 0, 0, true)).toBe(1)
    // mit Flackern: Aufflammen über 1 und kleine Schwankung
    expect(fireGlow(1, 1.25, 0, false)).toBeGreaterThan(1)
  })

  it('Windstoß kommt mit dem Pounce und ist beim Schlag vorbei', () => {
    for (const len of INTRO_LENGTHS) {
      const c = FOXDEN_CUES[len]
      expect(gustAt(c, c.pounce[2] - 0.2)).toBe(0)
      expect(gustAt(c, c.pounce[2] + 0.3)).toBeGreaterThan(0.9)
      expect(gustAt(c, c.impact)).toBe(0)
    }
  })

  it('Fichten wie in Minecraft: Spitze einzeln, nach unten breiter (mit Einschnitten)', () => {
    for (const tiers of [4, 6, 9, 11]) {
      const h = Array.from({ length: tiers }, (_, i) => spruceHalf(i, tiers))
      expect(h[0]).toBe(0)
      expect(h[tiers - 1]).toBe(Math.max(...h))
      expect(Math.max(...h)).toBeGreaterThan(0)
    }
  })
})

describe('Start-Animation Fuchsbau: Effekte', () => {
  it('Fahrplan sortiert, Tiefschlag auf dem Schlag, Pounce mit Fiepen und Rascheln', () => {
    for (const len of INTRO_LENGTHS) {
      const c = FOXDEN_CUES[len]
      const plan = planFoxden(c)
      for (let i = 1; i < plan.length; i++) expect(plan[i]!.t).toBeGreaterThanOrEqual(plan[i - 1]!.t)
      for (const e of plan) expect(e.t).toBeGreaterThanOrEqual(-1e-4)
      expect(plan.some((e) => e.kind === 'subHit' && e.t === c.impact)).toBe(true)
      expect(plan.some((e) => e.kind === 'yip' && e.t === c.pounce[1])).toBe(true)
      expect(plan.some((e) => e.kind === 'rustle' && e.t === c.pounce[2])).toBe(true)
      expect(plan.some((e) => e.kind === 'crackle')).toBe(true)
      // Zupfer sind musikalisch und laufen nur ohne Jingle
      for (const e of plan) if (e.kind === 'pluck') {
        expect(e.solo).toBe(true)
        expect(FOX_SCALE as readonly number[]).toContain(e.note)
      }
    }
    // lange Fassung: ein Plopp je Junges
    const long = planFoxden(FOXDEN_CUES.long)
    for (const k of FOXDEN_CUES.long.kits) expect(long.some((e) => e.kind === 'pop' && e.t === k)).toBe(true)
  })
})
