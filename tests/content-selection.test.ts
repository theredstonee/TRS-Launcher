import { describe, expect, it } from 'vitest'
import { rangeBetween } from '../app/utils/contentSelection'

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
