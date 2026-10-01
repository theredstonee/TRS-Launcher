import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import {
  BAT_EVERY_MS,
  BROOM,
  SPARK_COLORS,
  WITCH_EVERY_MS,
  WITCH_FRAME,
  WITCH_FRAMES,
  WITCH_FRAME_MS,
  WITCH_LTR,
  WITCH_SCALES,
  batPose,
  broomTip,
  flyAllowed,
  gapMs,
  makeSpark,
  planSwarm,
  planWitch,
  sparkAlpha,
  stepSpark,
  swarmCount,
  witchFrameRect,
  witchPose,
} from '../app/utils/halloween-sky'

function seq(values: number[]): () => number {
  let i = 0
  return () => values[Math.min(i++, values.length - 1)] ?? 0
}

describe('Halloween-Himmel', () => {
  it('fliegt nur, wenn Bewegung erlaubt und der Tab sichtbar ist', () => {
    expect(flyAllowed({ reduced: false, hidden: false })).toBe(true)
    expect(flyAllowed({ reduced: true, hidden: false })).toBe(false)
    expect(flyAllowed({ reduced: false, hidden: true })).toBe(false)
  })

  it('legt die Pausen in die vorgegebenen Spannen', () => {
    expect(WITCH_EVERY_MS).toEqual([25_000, 45_000])
    expect(BAT_EVERY_MS).toEqual([40_000, 70_000])
    expect(gapMs(WITCH_EVERY_MS, () => 0)).toBe(25_000)
    expect(gapMs(WITCH_EVERY_MS, () => 1)).toBe(45_000)
    expect(gapMs(BAT_EVERY_MS, () => 0)).toBe(40_000)
    expect(gapMs(BAT_EVERY_MS, () => 1)).toBe(70_000)
  })

  it('hält die Hexe in den oberen 60 %, Maßstab 2–3, meist nach rechts', () => {
    const view = { w: 1280, h: 800 }
    const right = planWitch(view, seq([0, 0, 0.5, 0.5]))
    expect(right.dir).toBe(1)
    expect(right.scale).toBe(WITCH_SCALES[0])
    expect(right.y).toBeGreaterThanOrEqual(0)
    expect(right.y + WITCH_FRAME * right.scale).toBeLessThanOrEqual(view.h * 0.6 + 0.01)

    const left = planWitch(view, seq([0.99, WITCH_LTR, 1, 0]))
    expect(left.dir).toBe(-1)
    expect(left.scale).toBe(3)
    expect(left.y + WITCH_FRAME * left.scale).toBeLessThanOrEqual(view.h * 0.6 + 0.01)
    expect(WITCH_LTR).toBeGreaterThan(0.7)
  })

  it('quert die Fläche und wippt sinusförmig', () => {
    const plan = { dir: 1 as const, y: 40, scale: 2, duration: 4_000, bob: 5 }
    const start = witchPose(plan, 0, 1000)
    const end = witchPose(plan, 4_000, 1000)
    expect(start.x).toBeCloseTo(-start.sz)
    expect(end.x).toBeCloseTo(1000)
    expect(end.alive).toBe(true)
    expect(witchPose(plan, 4_001, 1000).alive).toBe(false)
    const crest = witchPose(plan, 475, 1000)
    expect(crest.y).toBeGreaterThan(plan.y)
    expect(witchPose(plan, 0, 1000).frame).toBe(0)
    expect(witchPose(plan, WITCH_FRAME_MS, 1000).frame).toBe(1)
    expect(witchPose(plan, WITCH_FRAME_MS * WITCH_FRAMES, 1000).frame).toBe(0)
    expect(witchFrameRect(5).sx).toBe(5 * WITCH_FRAME)
    expect(witchFrameRect(6).sx).toBe(0)

    const back = { ...plan, dir: -1 as const }
    expect(witchPose(back, 0, 1000).x).toBeCloseTo(1000)
    expect(witchPose(back, 4_000, 1000).x).toBeCloseTo(-(WITCH_FRAME * back.scale))
  })

  it('legt Funken hinter den Besen, rot/orange, fallend', () => {
    const sz = 96
    expect(broomTip(10, 20, sz, 1).x).toBeCloseTo(10 + BROOM.u * sz)
    expect(broomTip(10, 20, sz, -1).x).toBeCloseTo(10 + (1 - BROOM.u) * sz)
    const behind = makeSpark({ x: 50, y: 80 }, 1, 0, () => 0.5)
    expect(behind.vx).toBeLessThan(0)
    expect(behind.vy).toBeGreaterThan(0)
    expect(SPARK_COLORS).toContain(behind.color)
    const ahead = makeSpark({ x: 50, y: 80 }, -1, 0, () => 0)
    expect(ahead.vx).toBeGreaterThan(0)
    const moved = { ...behind }
    stepSpark(moved, 1000)
    expect(moved.x).toBeLessThan(behind.x)
    expect(moved.y).toBeGreaterThan(behind.y)
    expect(sparkAlpha(behind, 0)).toBe(1)
    expect(sparkAlpha(behind, behind.life / 2)).toBeCloseTo(0.5)
    expect(sparkAlpha(behind, behind.life)).toBe(0)
  })

  it('schickt 2–4 Fledermäuse über den oberen Rand', () => {
    expect(swarmCount(() => 0)).toBe(2)
    expect(swarmCount(() => 0.999)).toBe(4)
    const swarm = planSwarm(() => 0)
    expect(swarm.bats).toHaveLength(2)
    expect(swarm.dir).toBe(1)
    const high = batPose(swarm, swarm.bats[0]!, 0, { w: 1000, h: 800 })
    expect(high.y).toBeLessThan(800 * 0.25)
    const far = batPose(swarm, { lane: 1, lag: 0, phase: 0, size: 2 }, 0, { w: 1000, h: 800 })
    expect(far.y).toBeLessThan(800 * 0.22)
    const full = planSwarm(seq([0.999, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 0.9, 0]))
    expect(full.bats.length).toBeGreaterThanOrEqual(2)
    expect(full.bats.length).toBeLessThanOrEqual(4)
  })

  it('bleibt im Stylesheet und in der Komponente an das Event und an reduzierte Bewegung gebunden', () => {
    const css = readFileSync(new URL('../app/assets/css/main.css', import.meta.url), 'utf8')
    const sky = readFileSync(new URL('../app/components/HalloweenSky.vue', import.meta.url), 'utf8')
    expect(css).toContain('html.theme-halloween .btn-primary')
    expect(css).toContain('html.theme-halloween body::before')
    expect(css).toContain('html.theme-halloween body::after')
    expect(css).toContain('html:not(.theme-halloween) .hw-sky')
    expect(css).not.toMatch(/style="/)
    expect(sky).toContain('prefers-reduced-motion')
    expect(sky).toContain('visibilityState')
    expect(sky).toContain('pointer-events: none')
    expect(sky).toContain('image-rendering: pixelated')
    expect(sky).not.toContain(':style')
    expect(sky.match(/requestAnimationFrame\(/g)?.length).toBe(2)
  })
})
