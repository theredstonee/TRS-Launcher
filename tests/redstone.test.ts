import { describe, expect, it } from 'vitest'
import { Circuit, parseStencil } from '../app/utils/redstone/sim'
import { buildScene } from '../app/utils/redstone/scene'

function circuit(rows: string[], delays: number[] = [], pulse = 1): Circuit {
  const stencil = parseStencil(rows, delays, pulse)
  const c = new Circuit(stencil[0]!.length, stencil.length)
  c.place(0, 0, stencil)
  return c.finish()
}

describe('Redstone-Simulation', () => {
  it('verliert je Staub-Block eine Stufe Signalstärke', () => {
    const c = circuit(['S. -- -- -- --'])
    expect([1, 2, 3, 4].map((x) => c.at(x, 0)!.power)).toEqual([15, 14, 13, 12])
  })

  it('verstärkt nach der eingestellten Verzögerung wieder auf 15', () => {
    const c = circuit(['S. -- R> -- --'], [3])
    expect(c.at(3, 0)!.power).toBe(0)
    c.step()
    c.step()
    expect(c.at(3, 0)!.power).toBe(0)
    c.step()
    expect(c.at(3, 0)!.power).toBe(15)
    expect(c.at(4, 0)!.power).toBe(14)
  })

  it('schaltet Lampe und Kolben und lässt die Lampe verzögert ausgehen', () => {
    const c = circuit(['S. -- L.', '.. -- P>'])
    c.step()
    expect(c.at(2, 0)!.on).toBe(true)
    expect(c.at(2, 1)!.on).toBe(true)
    c.at(0, 0)!.on = false
    c.updateDust()
    c.step()
    expect(c.at(2, 0)!.on).toBe(true)
    c.step()
    expect(c.at(2, 0)!.on).toBe(false)
    expect(c.at(2, 1)!.on).toBe(false)
  })

  it('invertiert mit einer Fackel am Block', () => {
    const c = circuit(['S. -- ## I< L.'])
    c.step()
    expect(c.at(3, 0)!.on).toBe(false)
    c.at(0, 0)!.on = false
    c.updateDust()
    c.step()
    expect(c.at(3, 0)!.on).toBe(true)
    c.step()
    expect(c.at(4, 0)!.on).toBe(true)
  })

  it('lässt einen Impuls im Takt-Ring dauerhaft kreisen', () => {
    const c = circuit(['-- r> -- -- L.', '-- .. .. -- ..', '-- -- R< -- ..'], [2, 2])
    let lit = 0
    for (let i = 0; i < 40; i++) {
      c.step()
      if (c.at(4, 0)!.on) lit++
    }
    expect(lit).toBeGreaterThan(5)
    expect(lit).toBeLessThan(40)
  })

  it('leitet die Hauptleitung nur bis zum Fortschritt', () => {
    const c = buildScene({ cols: 30, rows: 8, busRow: 6, busEnd: 26 })
    c.setExternal(true)
    c.busLimit = 10
    for (let i = 0; i < 20; i++) c.step()
    expect(c.at(5, 6)!.power).toBeGreaterThan(0)
    expect(c.at(12, 6)!.power).toBe(0)
    c.busLimit = Number.POSITIVE_INFINITY
    for (let i = 0; i < 20; i++) c.step()
    expect(c.at(25, 6)!.power).toBeGreaterThan(0)
  })
})

describe('Szene', () => {
  it('ist für dieselbe Größe immer gleich und hält Abstand zur Leitung', () => {
    const a = buildScene({ cols: 40, rows: 8, busRow: 6, busEnd: 32 })
    const b = buildScene({ cols: 40, rows: 8, busRow: 6, busEnd: 32 })
    expect(a.cells.map((c) => c.kind)).toEqual(b.cells.map((c) => c.kind))
    for (let x = 0; x < 40; x++) expect(a.at(x, 5)!.kind).toBe('floor')
    expect(a.at(31, 6)!.kind).toBe('dust')
    expect(a.cells.filter((c) => c.kind === 'lamp').length).toBeGreaterThan(2)
  })

  it('kommt mit sehr kleinen Flächen zurecht', () => {
    expect(() => buildScene({ cols: 3, rows: 2, busRow: 1, busEnd: 2 })).not.toThrow()
    expect(() => buildScene({ cols: 1, rows: 1, busRow: -1, busEnd: 0 })).not.toThrow()
  })
})
