import { describe, expect, it } from 'vitest'
import { fold, fuzzyMatch, highlight, rank } from '../app/utils/search'

describe('fuzzyMatch', () => {
  it('findet zusammenhängende und verstreute Buchstaben', () => {
    expect(fuzzyMatch('sky', 'Skyblock')).not.toBeNull()
    expect(fuzzyMatch('skb', 'Skyblock')).not.toBeNull()
    expect(fuzzyMatch('xyz', 'Skyblock')).toBeNull()
    // Reihenfolge zählt.
    expect(fuzzyMatch('ysk', 'Skyblock')).toBeNull()
  })

  it('ignoriert Groß-/Kleinschreibung und Umlaute', () => {
    // Die Faltung lässt die Länge unverändert, damit die Trefferstellen passen.
    expect(fold('Größe')).toBe('grose')
    expect(fuzzyMatch('grose', 'Größe')).not.toBeNull()
    expect(fuzzyMatch('groesse', 'Größe')).toBeNull()
    expect(fuzzyMatch('AUSSEHEN', 'Aussehen')).not.toBeNull()
  })

  it('leere Eingabe trifft alles', () => {
    expect(fuzzyMatch('  ', 'Egal')).toEqual({ score: 0, positions: [] })
  })

  it('bewertet den genaueren Treffer höher', () => {
    const exact = fuzzyMatch('fabric', 'Fabric')!
    const inside = fuzzyMatch('fabric', 'Meine alte Fabric-Welt')!
    const scattered = fuzzyMatch('fabric', 'Farben brauchen ich con')
    expect(exact.score).toBeGreaterThan(inside.score)
    if (scattered) expect(inside.score).toBeGreaterThan(scattered.score)
  })

  it('liefert die Trefferstellen für die Hervorhebung', () => {
    const hit = fuzzyMatch('sb', 'Skyblock')!
    expect(hit.positions).toEqual([0, 3])
  })

  it('bevorzugt Wortanfänge', () => {
    const hit = fuzzyMatch('ml', 'Minecraft Launcher')!
    expect(hit.positions).toEqual([0, 10])
  })
})

describe('rank', () => {
  const items = [
    { name: 'Survival 1.21', extra: 'fabric' },
    { name: 'Fabric Test', extra: '1.20.1' },
    { name: 'Hardcore', extra: 'vanilla' },
  ]
  const fields = (i: (typeof items)[number]) => [i.name, i.extra]

  it('sortiert Volltreffer im Haupttext vor Treffern in Nebenfeldern', () => {
    const result = rank(items, 'fabric', fields)
    expect(result[0]!.item.name).toBe('Fabric Test')
    expect(result.map((r) => r.item.name)).toContain('Survival 1.21')
    expect(result.map((r) => r.item.name)).not.toContain('Hardcore')
  })

  it('gibt ohne Eingabe alles unverändert zurück', () => {
    expect(rank(items, '', fields)).toHaveLength(3)
  })

  it('hebt nur Stellen aus dem Haupttext hervor', () => {
    const result = rank(items, '121', fields)
    expect(result[0]!.item.name).toBe('Survival 1.21')
    expect(result[0]!.positions.length).toBeGreaterThan(0)
  })
})

describe('highlight', () => {
  it('teilt den Text in Treffer und Rest', () => {
    expect(highlight('Skyblock', [0, 3])).toEqual([
      { text: 'S', hit: true },
      { text: 'ky', hit: false },
      { text: 'b', hit: true },
      { text: 'lock', hit: false },
    ])
  })

  it('ohne Treffer bleibt der Text ein Stück', () => {
    expect(highlight('Skyblock', [])).toEqual([{ text: 'Skyblock', hit: false }])
  })
})
