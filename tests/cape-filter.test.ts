import { describe, expect, it } from 'vitest'
import { capeOrigin, filterCapes } from '../app/utils/capeFilter'
import type { TrsCape } from '../app/utils/trs'

function cape(id: string, name: string, extra: Partial<TrsCape> = {}): TrsCape {
  return {
    id,
    name,
    kind: 'builtin',
    unlock: 'free',
    unlockEvent: null,
    status: 'approved',
    width: 64,
    height: 32,
    scale: 1,
    frames: 1,
    frameTimeMs: null,
    owned: true,
    active: false,
    rejectReason: null,
    texture: 'data:image/png;base64,AAAA',
    shareable: false,
    shared: null,
    holders: 0,
    ...extra,
  }
}

const list = [
  cape('b', 'Beta', { owned: false, unlock: 'code' }),
  cape('a', 'alpha', { frames: 8, frameTimeMs: 100 }),
  cape('c', 'Gamma', { kind: 'upload' }),
  cape('d', 'Delta', { shared: { from: { uuid: 'x', name: 'Fred' }, creator: { uuid: 'y', name: 'Gina' } }, kind: 'upload' }),
]
const ids = (l: TrsCape[]) => l.map((c) => c.id)

describe('Umhang-Galerie', () => {
  it('sucht im Namen ohne Groß-/Kleinschreibung', () => {
    expect(ids(filterCapes(list, '  ALP', 'all', 'default'))).toEqual(['a'])
    expect(filterCapes(list, 'zzz', 'all', 'default')).toEqual([])
  })
  it('filtert', () => {
    expect(ids(filterCapes(list, '', 'animated', 'default'))).toEqual(['a'])
    expect(ids(filterCapes(list, '', 'available', 'default'))).toEqual(['b'])
    expect(ids(filterCapes(list, '', 'owned', 'default'))).toEqual(['a', 'c', 'd'])
    expect(ids(filterCapes(list, '', 'friends', 'default'))).toEqual(['d'])
    expect(ids(filterCapes(list, '', 'own', 'default'))).toEqual(['c'])
  })
  it('sortiert, ohne die Eingabe zu ändern', () => {
    expect(ids(filterCapes(list, '', 'all', 'name'))).toEqual(['a', 'b', 'd', 'c'])
    expect(ids(filterCapes(list, '', 'all', 'nameDesc'))).toEqual(['c', 'd', 'b', 'a'])
    expect(ids(filterCapes(list, '', 'all', 'ownedFirst'))).toEqual(['a', 'd', 'c', 'b'])
    expect(ids(filterCapes(list, '', 'all', 'animatedFirst'))[0]).toBe('a')
    expect(ids(list)).toEqual(['b', 'a', 'c', 'd'])
  })
  it('erkennt die Herkunft', () => {
    expect(capeOrigin(list[0]!)).toBe('code')
    expect(capeOrigin(list[2]!)).toBe('upload')
    expect(capeOrigin(list[3]!)).toBe('friend')
    expect(capeOrigin(list[1]!)).toBe('free')
    expect(capeOrigin(cape('e', 'E', { unlock: 'event' }))).toBe('event')
    expect(capeOrigin(cape('f', 'F', { unlock: 'admin' }))).toBe('team')
  })
})
