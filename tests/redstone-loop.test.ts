import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SceneLoop } from '../app/utils/redstone/loop'
import { Rebuilder } from '../app/utils/redstone/rebuild'
import { buildScene, placements, rng } from '../app/utils/redstone/scene'
import type { Particle } from '../app/utils/redstone/paint'

describe('Takt der Redstone-Szene', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('läuft nur ohne Pausengrund und setzt nach jeder Pause fort', () => {
    let ticks = 0
    const loop = new SceneLoop({ tick: () => ticks++, interval: 100 })
    expect(loop.running).toBe(false)
    loop.set('hidden', false)
    expect(loop.running).toBe(true)
    vi.advanceTimersByTime(1000)
    expect(ticks).toBe(10)

    // Fenster minimiert, dann Szene aus dem Bild gescrollt.
    loop.set('hidden', true)
    loop.set('offscreen', true)
    vi.advanceTimersByTime(1000)
    expect(ticks).toBe(10)
    // Wiederhergestellt – aber noch aus dem Bild: steht weiter.
    loop.set('hidden', false)
    expect(loop.running).toBe(false)
    loop.set('offscreen', false)
    vi.advanceTimersByTime(500)
    expect(ticks).toBe(15)

    // Doppelte Meldungen starten keinen zweiten Takt.
    loop.set('offscreen', false)
    loop.set('hidden', false)
    vi.advanceTimersByTime(100)
    expect(ticks).toBe(16)

    loop.set('motion', true)
    vi.advanceTimersByTime(1000)
    expect(ticks).toBe(16)
    loop.set('motion', false)
    vi.advanceTimersByTime(100)
    expect(ticks).toBe(17)

    loop.dispose()
    loop.set('motion', false)
    vi.advanceTimersByTime(1000)
    expect(ticks).toBe(17)
    expect(loop.running).toBe(false)
  })

  it('bleibt nach einem Fehler im Tick nicht stehen und baut nach Fehlern in Folge neu auf', () => {
    let broken = true
    let ticks = 0
    const recover = vi.fn(() => {
      broken = false
    })
    const report = vi.fn()
    const loop = new SceneLoop({
      tick: () => {
        if (broken) throw new Error('kaputt')
        ticks++
      },
      interval: 100,
      recover,
      report,
    })
    loop.set('hidden', false)
    vi.advanceTimersByTime(300)
    expect(recover).toHaveBeenCalledTimes(1)
    expect(loop.running).toBe(true)
    vi.advanceTimersByTime(1000)
    expect(ticks).toBe(10)
    // Einzelner Ausrutscher: kein Neuaufbau, weiter geht's.
    broken = true
    vi.advanceTimersByTime(100)
    broken = false
    vi.advanceTimersByTime(100)
    expect(recover).toHaveBeenCalledTimes(1)
    expect(ticks).toBe(11)
  })

  it('meldet Dauerfehler nur ein paar Mal und läuft trotzdem weiter', () => {
    const report = vi.fn()
    const recover = vi.fn(() => {
      throw new Error('auch kaputt')
    })
    const loop = new SceneLoop({
      tick: () => {
        throw new Error('kaputt')
      },
      interval: 100,
      recover,
      report,
    })
    loop.set('offscreen', false)
    vi.advanceTimersByTime(10_000)
    expect(report).toHaveBeenCalledTimes(3)
    expect(recover.mock.calls.length).toBeGreaterThan(10)
    expect(loop.running).toBe(true)
  })
})

describe('Umbau der Szene', () => {
  it('baut über viele Größen und Seeds ab und wieder auf, ohne zu scheitern', () => {
    let finished = 0
    for (const [cols, rows] of [
      [40, 14],
      [24, 9],
      [12, 5],
      [48, 24],
      [3, 2],
    ] as const) {
      for (let seed = 1; seed <= 4; seed++) {
        const circuit = buildScene({ cols, rows, busRow: Math.floor(rows / 2), busEnd: Math.floor(cols / 2), seed, fill: seed % 2 === 0 })
        const size = circuit.cells.length
        const rebuilder = new Rebuilder(rng(seed * 7919), 5, 5)
        const particles: Particle[] = []
        let wasActive = false
        for (let tick = 1; tick <= 1200; tick++) {
          rebuilder.step(circuit, tick, particles)
          circuit.step()
          if (rebuilder.active) wasActive = true
          else if (wasActive) {
            wasActive = false
            finished++
          }
          particles.length = 0
        }
        expect(circuit.cells.length).toBe(size)
        expect(circuit.cells.every((c) => c !== undefined)).toBe(true)
        expect(placements.get(circuit)).toBeDefined()
      }
    }
    expect(finished).toBeGreaterThan(20)
  }, 30_000)

  it('Strg+Alt+R löst sofort einen Umbau aus, reset bricht ihn ab', () => {
    const circuit = buildScene({ cols: 40, rows: 16, busRow: 8, busEnd: 20, seed: 3, fill: true })
    const rebuilder = new Rebuilder(rng(1))
    expect(rebuilder.nextAt).toBeGreaterThanOrEqual(1800)
    rebuilder.trigger(10)
    rebuilder.step(circuit, 10, [])
    expect(rebuilder.active).toBe(true)
    rebuilder.reset()
    expect(rebuilder.active).toBe(false)
  })
})
