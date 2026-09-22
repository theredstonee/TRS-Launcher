import { describe, expect, it } from 'vitest'
import { categoryProjectType, environmentLabel, pageCount, pageItems, svgIconUrl } from '../app/utils/discover'
import { modrinthSearchSchema } from '../app/utils/schemas'
import type { ModrinthSearchParams } from '../app/types'

const base: ModrinthSearchParams = {
  query: '',
  kind: 'mod',
  gameVersions: ['1.21.11'],
  loaders: ['fabric'],
  categories: [],
  categoryMatch: 'all',
  excludeCategories: [],
  environments: [],
  excludeProjectIds: [],
  openSource: false,
  index: 'relevance',
  offset: 0,
  limit: 20,
}

describe('Seitenleiste', () => {
  it('zeigt wenige Seiten vollständig', () => {
    expect(pageItems(1, 1)).toEqual([1])
    expect(pageItems(3, 7)).toEqual([1, 2, 3, 4, 5, 6, 7])
  })

  it('kürzt lange Listen mit Auslassungen', () => {
    expect(pageItems(1, 94)).toEqual([1, 2, 3, 4, 5, null, 94])
    expect(pageItems(50, 94)).toEqual([1, null, 49, 50, 51, null, 94])
    expect(pageItems(93, 94)).toEqual([1, null, 90, 91, 92, 93, 94])
  })

  it('berücksichtigt Modrinths Offset-Grenze', () => {
    expect(pageCount(0, 20)).toBe(1)
    expect(pageCount(41, 20)).toBe(3)
    expect(pageCount(1_000_000, 100)).toBe(101)
  })
})

describe('Umgebung', () => {
  it('liest client_side/server_side wie Modrinth', () => {
    expect(environmentLabel({ clientSide: 'required', serverSide: 'unsupported' })).toBe('Client')
    expect(environmentLabel({ clientSide: 'unsupported', serverSide: 'required' })).toBe('Server')
    expect(environmentLabel({ clientSide: 'optional', serverSide: 'optional' })).toBe('Client oder Server')
    expect(environmentLabel({ clientSide: 'required', serverSide: 'required' })).toBe('Client und Server')
    expect(environmentLabel({ clientSide: 'unknown', serverSide: 'unknown' })).toBeNull()
  })
})

describe('Kategorie-Icons', () => {
  it('baut eine Data-URL mit Namespace und fester Farbe', () => {
    const url = svgIconUrl('<svg viewBox="0 0 24 24" stroke="currentColor"><path d="M1 1"/></svg>', '#8b8ba2')!
    expect(url.startsWith('data:image/svg+xml;charset=utf-8,')).toBe(true)
    const svg = decodeURIComponent(url.split(',')[1]!)
    expect(svg).toContain('xmlns="http://www.w3.org/2000/svg"')
    expect(svg).toContain('stroke="#8b8ba2"')
    expect(svg).not.toContain('currentColor')
  })

  it('lehnt Fremdes und ungültige Farben ab', () => {
    expect(svgIconUrl('<div></div>', '#fff')).toBeNull()
    expect(svgIconUrl('<svg></svg>', 'red;background:url(x)')).toBeNull()
  })

  it('ordnet Arten den Modrinth-Kategorien zu', () => {
    expect(categoryProjectType('shaderpack')).toBe('shader')
    expect(categoryProjectType('datapack')).toBe('mod')
    expect(categoryProjectType('resourcepack')).toBe('resourcepack')
  })
})

describe('Suchparameter', () => {
  it('akzeptiert gültige Filter', () => {
    const ok = {
      ...base,
      kind: 'datapack',
      categories: ['magic', '512x+'],
      excludeCategories: ['library'],
      environments: ['client', 'server'],
      excludeProjectIds: ['AANobbMI', 'fabric-api'],
      index: 'updated',
      limit: 100,
    }
    expect(modrinthSearchSchema.safeParse(ok).success).toBe(true)
  })

  it('lehnt alles außerhalb der Whitelist ab', () => {
    const bad: Record<string, unknown>[] = [
      { index: 'random' },
      { kind: 'plugin' },
      { limit: 101 },
      { limit: 0 },
      { offset: 10_001 },
      { query: 'x'.repeat(101) },
      { loaders: ['bukkit'] },
      { gameVersions: ['1.21"]'] },
      { categories: ['Magic'] },
      { excludeProjectIds: ['../x'] },
      { environments: ['client', 'server', 'client'] },
    ]
    for (const patch of bad) {
      expect(modrinthSearchSchema.safeParse({ ...base, ...patch }).success, JSON.stringify(patch)).toBe(false)
    }
  })
})
