import { describe, expect, it } from 'vitest'
import {
  FLY_BATS_EVERY_MS,
  FLY_WITCH_EVERY_MS,
  createFlySwarm,
  createFlyWitch,
  drawPixelBat,
  flyAlpha,
  flyGap,
  flyWitchBob,
  flyWitchX,
  stepFlySwarm,
  stepFlyWitch,
} from '../app/utils/halloweenFly'

function scripted(values: number[]): () => number {
  let i = 0
  return () => values[i++] ?? 0
}

describe('Halloween-Flug', () => {
  it('lost die Pausen in den vorgegebenen Spannen', () => {
    expect(flyGap(FLY_WITCH_EVERY_MS.min, FLY_WITCH_EVERY_MS.max, () => 0)).toBe(25_000)
    expect(flyGap(FLY_WITCH_EVERY_MS.min, FLY_WITCH_EVERY_MS.max, () => 1)).toBe(45_000)
    expect(flyGap(FLY_BATS_EVERY_MS.min, FLY_BATS_EVERY_MS.max, () => 0)).toBe(40_000)
    expect(flyGap(FLY_BATS_EVERY_MS.min, FLY_BATS_EVERY_MS.max, () => 1)).toBe(70_000)
    const mid = flyGap(25_000, 45_000, () => 0.25)
    expect(mid).toBe(30_000)
  })

  it('hält die Hexe im oberen Band, meist von links nach rechts', () => {
    const left = createFlyWitch(1280, 800, scripted([0, 0.5, 0, 0, 0]))
    expect(left.scale).toBe(2)
    expect(left.dir).toBe(1)
    expect(left.y).toBeGreaterThanOrEqual(0)
    expect(left.y + 48 * left.scale).toBeLessThanOrEqual(800 * 0.6)

    const back = createFlyWitch(1280, 800, scripted([0.9, 1, 0.95, 0, 0]))
    expect(back.scale).toBe(3)
    expect(back.dir).toBe(-1)
    expect(back.y + 48 * back.scale).toBeLessThanOrEqual(800 * 0.6 + 0.01)

    let rightward = 0
    const rand = mulberry(7)
    for (let i = 0; i < 200; i++) if (createFlyWitch(1000, 700, rand).dir === 1) rightward++
    expect(rightward).toBeGreaterThan(140)
  })

  it('fliegt einmal quer und lässt die Funken verblassen', () => {
    const w = createFlyWitch(800, 600, () => 0)
    expect(w.dir).toBe(1)
    const start = flyWitchX(w, 800)
    expect(start).toBeLessThan(0)
    let alive = true
    let spawned = false
    while (alive && w.elapsed < 30_000) {
      alive = stepFlyWitch(w, 50, 800, () => 0.2)
      if (w.sparks.length) spawned = true
    }
    expect(alive).toBe(false)
    expect(flyWitchX(w, 800)).toBeGreaterThan(800)
    expect(spawned).toBe(true)
    // Nach dem Flug keine lebenden Funken mehr übrig lassen, wenn man weiter stepped – der Flug ist vorbei.
    expect(w.sparks.every((s) => s.life > 0)).toBe(true)
    const fading = w.sparks[0]
    if (fading) {
      const before = fading.life
      fading.life -= 0.5
      expect(fading.life).toBeLessThan(before)
    }
    expect(Math.abs(flyWitchBob(w))).toBeLessThanOrEqual(5)
  })

  it('spiegelt den Rückflug und schickt die Funken hinter den Besen', () => {
    const w = createFlyWitch(900, 600, scripted([0.9, 0, 0.9, 0, 0]))
    expect(w.dir).toBe(-1)
    expect(flyWitchX(w, 900)).toBeGreaterThan(900)
    // Ein Schritt ist auf 50 ms gedeckelt; ein paar davon, bis sie im Bild ist.
    for (let i = 0; i < 30 && w.sparks.length === 0; i++) stepFlyWitch(w, 50, 900, () => 0)
    const spark = w.sparks[0]
    expect(spark).toBeTruthy()
    // Rechts → links: der Besen zeigt nach dem Spiegeln nach rechts, Funken driften nach rechts (vx > 0).
    expect(spark!.vx).toBeGreaterThan(0)
  })

  it('schickt 2 bis 4 Fledermäuse über den oberen Rand', () => {
    const few = createFlySwarm(1000, 700, () => 0)
    expect(few.bats).toHaveLength(2)
    expect(few.bats.every((b) => b.y < 700 * 0.45)).toBe(true)
    expect(few.bats.every((b) => b.vx > 0)).toBe(true)

    const many = createFlySwarm(1000, 700, scripted([0.99, 0.9]))
    expect(many.bats).toHaveLength(4)
    expect(many.bats.every((b) => b.vx < 0)).toBe(true)

    const startX = few.bats[0]!.x
    expect(stepFlySwarm(few, 100)).toBe(true)
    expect(few.bats[0]!.x).toBeGreaterThan(startX)
    few.elapsed = few.duration
    expect(stepFlySwarm(few, 1)).toBe(false)
  })

  it('blendet an den Rändern ein und aus', () => {
    expect(flyAlpha(0, 1000)).toBe(0)
    expect(flyAlpha(1000, 1000)).toBe(0)
    expect(flyAlpha(500, 1000)).toBe(1)
    expect(flyAlpha(20, 1000)).toBeGreaterThan(0)
    expect(flyAlpha(20, 1000)).toBeLessThan(1)
  })

  it('zeichnet die Fledermaus aus der Startseiten-Szene mit Körper und Flügeln', () => {
    const ops: string[] = []
    const ctx = {
      fillStyle: '',
      fillRect(x: number, y: number, w: number, h: number) {
        ops.push(`${x},${y},${w},${h}`)
      },
    } as CanvasRenderingContext2D
    drawPixelBat(ctx, 10, 20, true, 1)
    expect(ops).toEqual(['10,20,6,4', '4,17,6,2', '16,17,6,2'])
  })
})

function mulberry(seed: number): () => number {
  let s = seed >>> 0
  return () => {
    s = (s + 0x6d2b79f5) >>> 0
    let t = s
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
