import { describe, expect, it } from 'vitest'
import type { Instance, LoaderKind } from '../app/types'
import { compareGameVersions, customGroups, filterInstances, groupInstances, majorVersion, sortInstances } from '../app/utils/library'

function inst(name: string, gameVersion: string, kind: LoaderKind, extra: Partial<Instance> = {}): Instance {
  return {
    id: name.toLowerCase(),
    name,
    gameVersion,
    loader: { kind, version: null },
    createdAt: '2026-01-01T00:00:00Z',
    lastPlayed: null,
    totalPlaySeconds: 0,
    overrides: {} as Instance['overrides'],
    ...extra,
  }
}

const list = [
  inst('Alpha', '1.8.9', 'forge', { group: 'PvP', totalPlaySeconds: 50, createdAt: '2026-03-01T00:00:00Z' }),
  inst('beta', '1.21.11', 'fabric', { lastPlayed: '2026-09-20T00:00:00Z', totalPlaySeconds: 500 }),
  inst('Gamma', '26.1', 'fabric', { group: 'Technik', lastPlayed: '2026-09-21T00:00:00Z' }),
  inst('Delta', '1.21.1', 'vanilla', { group: 'PvP', createdAt: '2026-05-01T00:00:00Z' }),
]
const names = (l: Instance[]) => l.map((i) => i.name)

describe('Bibliothek', () => {
  it('vergleicht Versionen numerisch und per Manifest', () => {
    expect(compareGameVersions('1.21.11', '1.21.2')).toBeLessThan(0)
    expect(compareGameVersions('26.1', '1.21.11')).toBeLessThan(0)
    expect(compareGameVersions('1.8.9', '1.21')).toBeGreaterThan(0)
    const order = new Map([['1.8.9', 0], ['1.21', 1]])
    expect(compareGameVersions('1.8.9', '1.21', order)).toBeLessThan(0)
    expect(majorVersion('1.21.11')).toBe('1.21')
    expect(majorVersion('26.1.2')).toBe('26.1')
  })

  it('sortiert nach allen Kriterien', () => {
    expect(names(sortInstances(list, 'name'))).toEqual(['Alpha', 'beta', 'Delta', 'Gamma'])
    expect(names(sortInstances(list, 'played'))).toEqual(['Gamma', 'beta', 'Alpha', 'Delta'])
    expect(names(sortInstances(list, 'playtime'))).toEqual(['beta', 'Alpha', 'Delta', 'Gamma'])
    expect(names(sortInstances(list, 'created'))).toEqual(['Delta', 'Alpha', 'beta', 'Gamma'])
    expect(names(sortInstances(list, 'version'))).toEqual(['Gamma', 'beta', 'Delta', 'Alpha'])
  })

  it('filtert nach Suche, Loader und Version', () => {
    expect(names(filterInstances(list, { query: 'fabric', loaders: [], versions: [] }))).toEqual(['beta', 'Gamma'])
    expect(names(filterInstances(list, { query: 'pvp', loaders: [], versions: [] }))).toEqual(['Alpha', 'Delta'])
    expect(names(filterInstances(list, { query: '', loaders: ['vanilla', 'forge'], versions: [] }))).toEqual(['Alpha', 'Delta'])
    expect(names(filterInstances(list, { query: '', loaders: [], versions: ['1.21'] }))).toEqual(['beta', 'Delta'])
  })

  it('gruppiert nach Loader, Version und eigenen Gruppen', () => {
    const byLoader = groupInstances(list, 'loader')
    expect(byLoader.map((g) => g.label)).toEqual(['Vanilla', 'Fabric', 'Forge'])
    const byVersion = groupInstances(sortInstances(list, 'version'), 'version')
    expect(byVersion.map((g) => g.label)).toEqual(['Minecraft 26.1', 'Minecraft 1.21', 'Minecraft 1.8'])
    const custom = groupInstances(sortInstances(list, 'name'), 'custom')
    expect(custom.map((g) => [g.label, names(g.items)])).toEqual([
      ['PvP', ['Alpha', 'Delta']],
      ['Technik', ['Gamma']],
      ['Ohne Gruppe', ['beta']],
    ])
    expect(groupInstances(list, 'none')).toHaveLength(1)
    expect(customGroups(list)).toEqual(['PvP', 'Technik'])
  })
})
