import { describe, expect, it } from 'vitest'
import type { ContentItem } from '../app/types'
import {
  buildSections,
  contentOrganizationSchema,
  groupNameSchema,
  groupToggleState,
  indexOrganization,
  matchesFilters,
  sortedGroups,
  type ContentOrganization,
} from '../app/utils/contentGroups'
import { bisectPercent, bisectViewSchema } from '../app/utils/bisect'

function item(fileName: string, extra: Partial<ContentItem> = {}): ContentItem {
  return { fileName, kind: 'mod', enabled: true, size: 1, title: null, version: null, description: null, source: null, author: null, iconUrl: null, slug: null, ...extra }
}

const sodium = item('sodium.jar', { title: 'Sodium' })
const lithium = item('lithium.jar', { title: 'Lithium', enabled: false })
const jei = item('jei.jar', { title: 'JEI' })
const faithful = item('faithful.zip', { kind: 'resourcepack', title: 'Faithful' })
const all = [sodium, lithium, jei, faithful]

const org: ContentOrganization = {
  groups: [
    { id: 'perf', name: 'Performance', color: 'green', collapsed: false },
    { id: 'empty', name: 'Leer', color: 'gray', collapsed: true },
  ],
  assignments: [
    { kind: 'mod', fileName: 'sodium.jar', groupId: 'perf' },
    { kind: 'mod', fileName: 'lithium.jar', groupId: 'perf' },
  ],
  fromPack: [{ kind: 'mod', fileName: 'sodium.jar' }, { kind: 'resourcepack', fileName: 'faithful.zip' }],
  packKnown: true,
}

describe('content groups', () => {
  it('validates what the core sends', () => {
    expect(contentOrganizationSchema.parse(org).groups).toHaveLength(2)
    expect(() => contentOrganizationSchema.parse({ ...org, groups: [{ id: '../x', name: 'A', color: 'red', collapsed: false }] })).toThrow()
    expect(() => contentOrganizationSchema.parse({ ...org, groups: [{ id: 'a', name: 'A', color: 'neon', collapsed: false }] })).toThrow()
  })

  it('checks group names like the core', () => {
    expect(groupNameSchema.parse('  Optik ')).toBe('Optik')
    for (const bad of ['', '   ', 'a\nb', 'x'.repeat(41)]) expect(groupNameSchema.safeParse(bad).success, bad).toBe(false)
  })

  it('filters by origin, group and search (also the group name)', () => {
    const index = indexOrganization(org)
    const pick = (opts: Parameters<typeof matchesFilters>[2]) => all.filter((i) => matchesFilters(i, index, opts)).map((i) => i.fileName)
    expect(pick({ needle: '', origin: 'pack', group: 'all' })).toEqual(['sodium.jar', 'faithful.zip'])
    expect(pick({ needle: '', origin: 'manual', group: 'all' })).toEqual(['lithium.jar', 'jei.jar'])
    expect(pick({ needle: '', origin: 'all', group: 'perf' })).toEqual(['sodium.jar', 'lithium.jar'])
    expect(pick({ needle: '', origin: 'all', group: 'none' })).toEqual(['jei.jar', 'faithful.zip'])
    expect(pick({ needle: 'performance', origin: 'all', group: 'all' })).toEqual(['sodium.jar', 'lithium.jar'])
    expect(pick({ needle: 'jei', origin: 'manual', group: 'none' })).toEqual(['jei.jar'])
  })

  it('builds sections: groups first, empty groups only without filters', () => {
    const sections = buildSections(all, all, org, false)
    expect(sections.map((s) => s.group?.id ?? null)).toEqual(['perf', 'empty', null])
    expect(sections[0]!.items.map((i) => i.fileName)).toEqual(['sodium.jar', 'lithium.jar'])
    expect(sections[2]!.items.map((i) => i.fileName)).toEqual(['jei.jar', 'faithful.zip'])

    // Gefiltert: leere Abschnitte fallen weg, Zähler/Schalter sehen trotzdem die ganze Gruppe.
    const filtered = buildSections([sodium], all, org, true)
    expect(filtered.map((s) => s.group?.id ?? null)).toEqual(['perf'])
    expect(filtered[0]!.all).toHaveLength(2)

    // Zuordnung zu einer gelöschten Gruppe zählt als „ohne Gruppe“.
    const stale = { ...org, assignments: [{ kind: 'mod' as const, fileName: 'jei.jar', groupId: 'weg' }] }
    expect(buildSections([jei], [jei], stale, true).map((s) => s.group)).toEqual([null])
  })

  it('reports the group switch state', () => {
    expect(groupToggleState([sodium, jei])).toBe('on')
    expect(groupToggleState([sodium, lithium])).toBe('mixed')
    expect(groupToggleState([lithium])).toBe('off')
    expect(groupToggleState([])).toBe('off')
  })

  it('sorts groups by name for menus', () => {
    expect(sortedGroups(org.groups).map((g) => g.name)).toEqual(['Leer', 'Performance'])
  })
})

describe('bisect view', () => {
  const view = {
    instanceId: 'test',
    startedAt: '2026-10-03T10:00:00Z',
    round: 3,
    estimatedRounds: 5,
    phase: 'testing',
    total: 30,
    suspects: [{ fileName: 'a.jar', title: 'A', iconUrl: null, source: { projectId: 'AANobbMI', versionId: 'v1' }, slug: 'a' }],
    testing: 1,
    disabled: 12,
    result: [],
  }

  it('parses the core view and rejects unknown phases', () => {
    expect(bisectViewSchema.parse(view).suspects[0]!.source?.projectId).toBe('AANobbMI')
    expect(() => bisectViewSchema.parse({ ...view, phase: 'weird' })).toThrow()
  })

  it('shows progress per round and 100 % at the end', () => {
    const parsed = bisectViewSchema.parse(view)
    expect(bisectPercent(parsed)).toBe(40)
    expect(bisectPercent({ ...parsed, round: 1 })).toBe(0)
    expect(bisectPercent({ ...parsed, round: 9 })).toBe(95)
    expect(bisectPercent({ ...parsed, phase: 'found' })).toBe(100)
  })
})
