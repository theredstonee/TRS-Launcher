import { describe, expect, it } from 'vitest'
import { applyRange, rangeAction, rangeBetween } from '../app/utils/contentSelection'

const keys = ['a', 'b', 'c', 'd', 'e']

describe('Bereichsauswahl', () => {
  it('nimmt beide Enden und alles dazwischen', () => {
    expect(rangeBetween(keys, 'b', 'd')).toEqual(['b', 'c', 'd'])
  })

  it('geht auch nach oben', () => {
    expect(rangeBetween(keys, 'd', 'b')).toEqual(['b', 'c', 'd'])
  })

  it('derselbe Eintrag ist ein Bereich aus einem', () => {
    expect(rangeBetween(keys, 'c', 'c')).toEqual(['c'])
  })

  it('kein Bereich, wenn ein Ende nicht mehr sichtbar ist', () => {
    expect(rangeBetween(keys, 'x', 'c')).toBeNull()
    expect(rangeBetween(keys, 'c', 'x')).toBeNull()
  })
})

describe('Bereich an- und abwählen', () => {
  it('eine nicht ausgewählte Zeile wählt den Bereich aus', () => {
    expect(rangeAction(new Set(['a']), 'c')).toBe('select')
    expect([...applyRange(new Set(['a']), ['a', 'b', 'c'], 'select')].sort()).toEqual(['a', 'b', 'c'])
  })

  it('eine ausgewählte Zeile wählt den Bereich ab, der Rest bleibt', () => {
    const selected = new Set(['a', 'b', 'c', 'd', 'e'])
    expect(rangeAction(selected, 'd')).toBe('deselect')
    expect([...applyRange(selected, ['b', 'c', 'd'], 'deselect')].sort()).toEqual(['a', 'e'])
  })

  it('ändert die übergebene Auswahl nicht', () => {
    const selected = new Set(['a'])
    applyRange(selected, ['a'], 'deselect')
    expect([...selected]).toEqual(['a'])
  })
})
