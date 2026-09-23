import { describe, expect, it } from 'vitest'
import { MODULES, buildScene, moduleWidth, placements, planCable, rng, stencilFor } from '../app/utils/redstone/scene'
import { Circuit, displayOn, parseStencil, type StencilOptions } from '../app/utils/redstone/sim'

function circuit(rows: string[], opts: StencilOptions = {}, delays: number[] = []): Circuit {
  const stencil = parseStencil(rows, delays, 1, opts)
  const c = new Circuit(stencil[0]!.length, stencil.length)
  c.place(0, 0, stencil)
  return c.finish()
}

function run(c: Circuit, ticks: number, each?: (t: number) => void) {
  for (let t = 0; t < ticks; t++) {
    c.step()
    each?.(t)
  }
}

describe('Redstone-Maschinen', () => {
  it('Trichter-Uhr taktet mit Periode und Impulslänge', () => {
    const c = circuit(['H. -- L.'], { period: 30, clockPulse: 8, phase: 0 })
    const lamp = c.at(2, 0)!
    const on: boolean[] = []
    run(c, 60, () => on.push(lamp.on))
    // an im Impuls (plus Nachleuchten), dann lange aus – zweimal in 60 Ticks
    const rises = on.filter((v, i) => v && !on[i - 1]).length
    expect(rises).toBe(2)
    expect(on.filter(Boolean).length).toBeGreaterThan(10)
    expect(on.filter(Boolean).length).toBeLessThan(30)
  })

  it('Signal läuft mit endlicher Geschwindigkeit durch den Staub', () => {
    const c = circuit(['S. -- -- -- -- -- -- -- -- --'])
    // sofort (Standard): alles an
    expect(c.at(9, 0)!.power).toBe(7)
    const slow = new Circuit(10, 1)
    slow.place(0, 0, parseStencil(['.. -- -- -- -- -- -- -- -- --']))
    slow.dustSpeed = 2
    slow.finish()
    slow.set(0, 0, parseStencil(['S.'])[0]![0]!)
    slow.relink()
    slow.step()
    expect(slow.at(2, 0)!.power).toBeGreaterThan(0)
    expect(slow.at(9, 0)!.power).toBe(0)
    run(slow, 4)
    expect(slow.at(9, 0)!.power).toBe(7)
  })

  it('Beobachter gibt bei einer Änderung einen kurzen Impuls, der Notenblock klingt', () => {
    const c = circuit(['H. -- L. O> N.'], { period: 20, clockPulse: 6, phase: 0 })
    const notes: number[] = []
    run(c, 40, (t) => {
      for (const e of c.events) if (e.type === 'note') notes.push(t)
      c.events.length = 0
    })
    // an und aus je Takt → mindestens zwei Töne pro Periode
    expect(notes.length).toBeGreaterThanOrEqual(3)
  })

  it('Spender wirft bei steigender Flanke ein Item aus', () => {
    const c = circuit(['H. -- D> ..'], { period: 20, clockPulse: 5, phase: 0 })
    let items = 0
    run(c, 40, () => {
      items += c.events.filter((e) => e.type === 'item' && e.dir === 1).length
      c.events.length = 0
    })
    expect(items).toBe(2)
  })

  it('Klebekolben der Tür fahren im Takt aus', () => {
    const c = circuit(['Q> .. .. .. .. Q<', '-- -- -- -- -- --', '.. .. H. .. .. ..'], { period: 20, clockPulse: 8, phase: 0 })
    const left = c.at(0, 0)!
    const right = c.at(5, 0)!
    expect(left.sticky && right.sticky).toBe(true)
    let both = false
    run(c, 20, () => (both ||= left.on && right.on))
    expect(both).toBe(true)
  })

  it('TNT blinkt gezündet, sprüht Funken und geht wieder aus', () => {
    const c = circuit(['## X. ##'])
    c.prime(1, 0, 12)
    let sparks = 0
    let flashes = 0
    run(c, 20, () => {
      sparks += c.events.filter((e) => e.type === 'spark').length
      c.events.length = 0
      if (c.at(1, 0)!.on) flashes++
    })
    expect(sparks).toBeGreaterThan(0)
    expect(flashes).toBeGreaterThan(0)
    expect(c.at(1, 0)!.on).toBe(false)
  })

  it('Tageslichtsensor schaltet nachts die Lampen', () => {
    const c = circuit(['Y. -- L.'])
    run(c, 3)
    expect(c.at(2, 0)!.on).toBe(false)
    c.night = true
    run(c, 3)
    expect(c.at(2, 0)!.on).toBe(true)
  })

  it('Kettenreaktion lässt Lampen als Welle aufleuchten', () => {
    const c = circuit(['L. .. .. .. .. .. .. .. .. L.'])
    c.flashCol = 0
    c.step()
    expect(c.at(0, 0)!.on).toBe(true)
    expect(c.at(9, 0)!.on).toBe(false)
    run(c, 4)
    expect(c.at(9, 0)!.on).toBe(true)
  })

  it('Anzeige-Muster laufen über die Spalten', () => {
    const lit = (pattern: number, tick: number) => [0, 1, 2, 3, 4, 5].map((col) => displayOn(pattern, tick, col, 0, 6))
    expect(lit(1, 0)).toEqual([true, false, false, false, false, false])
    expect(lit(1, 8)).toEqual([false, false, true, false, false, false])
    expect(lit(2, 10).every(Boolean)).toBe(true)
    expect(lit(2, 61).some(Boolean)).toBe(false)
    expect(lit(0, 0)).not.toEqual(lit(0, 20))
  })

  it('jede Schablone ist rechteckig und lässt sich einsetzen', () => {
    const random = rng(42)
    for (const m of MODULES) {
      const widths = m.rows.map((r) => r.trim().split(/\s+/).length)
      expect(new Set(widths).size, m.name).toBe(1)
      const cells = stencilFor(m, random)
      expect(cells[0]!.length).toBe(moduleWidth(m))
    }
  })

  it('der Seiten-Hintergrund hat lange Kabel und versetzte Takt-Fackeln', () => {
    const c = buildScene({ cols: 60, rows: 30, busRow: -1, busEnd: 0, seed: 7, fill: true })
    const list = placements.get(c) ?? []
    const cables = list.filter((p) => p.name === 'cable')
    expect(cables.length).toBeGreaterThan(6)
    expect(cables.length).toBeGreaterThan(list.length - cables.length)
    expect(c.dustSpeed).toBe(2)
    const clocks = c.cells.filter((x) => x.kind === 'torch' && x.period > 0)
    expect(clocks.length).toBeGreaterThan(5)
    expect(new Set(clocks.map((h) => h.period)).size).toBeGreaterThan(2)
    // Perioden wie im Spiel: 2,4–5,6 s
    expect(clocks.every((k) => k.period >= 24 && k.period <= 56)).toBe(true)
  })

  it('ein Kabel ist zusammenhängend und berührt keine fremden Leitungen', () => {
    const c = buildScene({ cols: 50, rows: 24, busRow: -1, busEnd: 0, seed: 3, fill: true })
    for (const p of (placements.get(c) ?? []).filter((q) => q.name === 'cable')) {
      const own = new Set(p.cells)
      for (const i of p.cells!) {
        const x = i % c.w
        const y = (i / c.w) | 0
        const around = [[1, 0], [-1, 0], [0, 1], [0, -1]].map(([dx, dy]) => (y + dy!) * c.w + x + dx!)
        // jedes Feld hat mindestens einen eigenen Nachbarn, aber keinen fremden (außer Boden/Block)
        expect(around.some((n) => own.has(n))).toBe(true)
        for (const n of around) {
          const k = c.cells[n]?.kind
          if (!own.has(n)) expect(k === 'floor' || k === 'block' || k === undefined).toBe(true)
        }
      }
    }
    expect(planCable(c, rng(1))).toBeTypeOf('object')
  })
})
