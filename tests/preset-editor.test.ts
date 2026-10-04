import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import type { ModrinthHit, PresetCheck, PresetItem, PresetPickItem } from '../app/types'
import { i18n, setLocale } from '../app/utils/i18n'
import { icons } from '../app/utils/icons'
import {
  conflictText,
  conflictsOf,
  depsOf,
  emptyPickFilter,
  filterPicks,
  groupPresetItems,
  hitToItem,
  inPreset,
  isPickable,
  mergePicks,
  pickKey,
  presetColor,
  presetColorHex,
  presetIconPath,
  simpleName,
} from '../app/utils/presetEditor'
import { isPresetTemplateId, presetTemplateIds, presetTemplates, templateInput } from '../app/utils/presetTemplates'
import { PRESET_COLORS, PRESET_ICONS, presetCheckSchema, presetInputSchema, presetPickListSchema } from '../app/utils/schemas'

function pick(title: string, patch: Partial<PresetPickItem> = {}): PresetPickItem {
  return {
    source: 'modrinth',
    projectId: title.replace(/[^A-Za-z0-9]/g, '').slice(0, 8) || 'x',
    title,
    iconUrl: null,
    kind: 'mod',
    categories: [],
    performance: false,
    clientOnly: null,
    fileName: `${title}.jar`,
    ...patch,
  }
}

function item(projectId: string, title: string, patch: Partial<PresetItem> = {}): PresetItem {
  return { source: 'modrinth', projectId, title, iconUrl: null, kind: 'mod', ...patch }
}

const sodium = pick('Sodium', { projectId: 'AANobbMI', performance: true, clientOnly: true, categories: ['optimization'] })
const lithium = pick('Lithium', { projectId: 'gvQqBUqZ', performance: true, clientOnly: false })
const jei = pick('Just Enough Items', { source: 'curseforge', projectId: '238222' })
const loose = pick('handmade-1.0', { source: null, projectId: null })
const faithful = pick('Faithful 32x', { projectId: 'BdYrfSSx', kind: 'resourcepack' })
const shader = pick('Complementary Reimagined', { projectId: 'HVnmMxH1', kind: 'shaderpack' })
const all = [sodium, lithium, jei, loose, faithful, shader]

let before: string
beforeAll(async () => {
  before = i18n.global.locale.value
  await setLocale('en')
})
afterAll(async () => {
  await setLocale(before)
})

describe('picker filters', () => {
  it('filters by text, kind, performance and client-only', () => {
    expect(filterPicks(all, emptyPickFilter())).toHaveLength(all.length)
    expect(filterPicks(all, { ...emptyPickFilter(), query: 'SOD' }).map((p) => p.title)).toEqual(['Sodium'])
    // Auch der Dateiname zählt.
    expect(filterPicks(all, { ...emptyPickFilter(), query: 'handmade-1.0.jar' })).toEqual([loose])
    expect(filterPicks(all, { ...emptyPickFilter(), kind: 'resourcepack' })).toEqual([faithful])
    expect(filterPicks(all, { ...emptyPickFilter(), performance: true })).toEqual([sodium, lithium])
    expect(filterPicks(all, { ...emptyPickFilter(), clientOnly: true })).toEqual([sodium])
    expect(filterPicks(all, { query: 'lith', kind: 'mod', performance: true, clientOnly: true })).toEqual([])
  })

  it('only items with a project id can be taken over', () => {
    expect(isPickable(sodium)).toBe(true)
    expect(isPickable(jei)).toBe(true)
    expect(isPickable(loose)).toBe(false)
    expect(isPickable(pick('x', { source: 'curseforge', projectId: 'abc' }))).toBe(false)
    expect(isPickable(pick('x', { projectId: '../evil' }))).toBe(false)
    expect(pickKey(sodium)).toBe('modrinth:AANobbMI')
    expect(pickKey(jei)).toBe('curseforge:238222')
    expect(pickKey(loose)).toBe('file:mod:handmade-1.0.jar')
  })
})

describe('taking items over', () => {
  it('skips duplicates by id and by name, and items without project', () => {
    const existing = [item('AANobbMI', 'Sodium'), item('xxxxxxxx', 'Just Enough Items')]
    expect(inPreset(existing, sodium)).toBe(true)
    // Gleicher Name von CurseForge = dasselbe Projekt.
    expect(inPreset(existing, jei)).toBe(true)
    // Gleicher Name, andere Art: kein Duplikat.
    expect(inPreset([item('aaaaaaaa', 'Faithful 32x')], faithful)).toBe(false)

    const result = mergePicks(existing, [sodium, lithium, jei, loose, faithful, lithium])
    expect(result.added).toBe(2)
    expect(result.duplicates).toBe(3)
    expect(result.overflow).toBe(0)
    expect(result.items.map((i) => `${i.source}:${i.projectId}:${i.kind}`)).toEqual([
      'modrinth:AANobbMI:mod',
      'modrinth:xxxxxxxx:mod',
      'modrinth:gvQqBUqZ:mod',
      'modrinth:BdYrfSSx:resourcepack',
    ])
    // Das Original bleibt unverändert.
    expect(existing).toHaveLength(2)
  })

  it('stops at the item limit and drops foreign icon hosts', () => {
    const result = mergePicks([item('aaaaaaaa', 'A')], [sodium, lithium, shader], 2)
    expect(result).toMatchObject({ added: 1, overflow: 2 })
    const withIcon = mergePicks([], [pick('Iconic', { projectId: 'iconic01', iconUrl: 'https://evil.example/x.png' })])
    expect(withIcon.items[0]!.iconUrl).toBeNull()
    const cf = mergePicks([], [pick('CF', { source: 'curseforge', projectId: '42', iconUrl: 'https://media.forgecdn.net/a.png' })])
    expect(cf.items[0]).toEqual({ source: 'curseforge', projectId: '42', title: 'CF', iconUrl: 'https://media.forgecdn.net/a.png', kind: 'mod' })
    expect(presetInputSchema.safeParse({ name: 'X', auto: false, items: cf.items }).success).toBe(true)
  })
})

describe('templates', () => {
  it('are valid presets without duplicates', () => {
    expect(presetTemplateIds).toEqual(['performance', 'shader', 'pvp', 'redstone'])
    for (const id of presetTemplateIds) {
      const input = templateInput(id)
      const parsed = presetInputSchema.safeParse(input)
      expect(parsed.success, `${id}: ${parsed.success ? '' : parsed.error.message}`).toBe(true)
      expect(input.name).toBe(i18n.global.t(`presets.templates.${id}.name`))
      expect(input.items.length).toBeGreaterThanOrEqual(4)
      // Version-unabhängig: nur Modrinth-IDs (8 Zeichen), Symbole vom Modrinth-CDN des Projekts.
      for (const i of input.items) {
        expect(i.projectId).toMatch(/^[A-Za-z0-9]{8}$/)
        expect(i.iconUrl).toContain(`https://cdn.modrinth.com/data/${i.projectId}/`)
      }
      expect(PRESET_ICONS).toContain(presetTemplates[id].icon)
      expect(PRESET_COLORS).toContain(presetTemplates[id].color)
    }
  })

  it('give each preset its own copy and fit their topic', () => {
    const a = templateInput('performance')
    a.items.pop()
    expect(templateInput('performance').items).toHaveLength(presetTemplates.performance.items.length)
    // Shader-Vorlage: Iris plus Shaderpakete; keine zwei Render-Mods in „Performance“.
    expect(presetTemplates.shader.items.some((i) => i.projectId === 'YL57xq9U')).toBe(true)
    expect(presetTemplates.shader.items.filter((i) => i.kind === 'shaderpack').length).toBeGreaterThanOrEqual(2)
    expect(presetTemplates.performance.items.some((i) => i.projectId === 'sk9rgfiA')).toBe(false)
    expect(isPresetTemplateId('pvp')).toBe(true)
    expect(isPresetTemplateId('__proto__')).toBe(false)
    expect(isPresetTemplateId(['pvp'])).toBe(false)
  })
})

describe('look', () => {
  it('every icon exists and every colour has a value', () => {
    for (const name of PRESET_ICONS) expect(icons[name as keyof typeof icons], name).toBeTruthy()
    for (const c of PRESET_COLORS) expect(presetColorHex[c]).toMatch(/^#[0-9a-f]{6}$/)
    expect(presetColor('neon')).toBe(presetColorHex.redstone)
    expect(presetIconPath('nope')).toBe(icons.presets)
    // Feste Symbole der TRS-Presets gibt es auch.
    for (const fixed of ['bolt', 'monitor', 'chat', 'record']) expect(presetIconPath(fixed)).toBe(icons[fixed as keyof typeof icons])
  })

  it('schema accepts known icon/colour and rejects others', () => {
    const base = { name: 'A', auto: false, items: [] }
    expect(presetInputSchema.safeParse({ ...base, icon: 'sword', color: 'sky' }).success).toBe(true)
    expect(presetInputSchema.safeParse({ ...base, icon: null, color: null }).success).toBe(true)
    expect(presetInputSchema.safeParse(base).success).toBe(true)
    expect(presetInputSchema.safeParse({ ...base, icon: '<svg>' }).success).toBe(false)
    expect(presetInputSchema.safeParse({ ...base, color: '#ff0000' }).success).toBe(false)
    // Gleiche ID auf zwei Plattformen sind zwei Projekte; doppelt auf einer nicht erlaubt.
    const mr = item('238222', 'A')
    const cf = item('238222', 'B', { source: 'curseforge' })
    expect(presetInputSchema.safeParse({ ...base, items: [mr, cf] }).success).toBe(true)
    expect(presetInputSchema.safeParse({ ...base, items: [cf, cf] }).success).toBe(false)
    expect(presetInputSchema.safeParse({ ...base, items: [item('0123', 'C', { source: 'curseforge' })] }).success).toBe(false)
  })
})

describe('dependencies and conflicts', () => {
  const items = [item('51shyZVL', 'More Culling'), item('AANobbMI', 'Sodium'), item('sk9rgfiA', 'Embeddium'), item('9s6osm5g', 'Cloth Config API')]
  const check: PresetCheck = presetCheckSchema.parse({
    deps: [
      {
        source: 'modrinth',
        projectId: '51shyZVL',
        deps: [
          { source: 'modrinth', projectId: '9s6osm5g', title: 'Cloth Config API', iconUrl: null, loaders: [] },
          { source: 'modrinth', projectId: 'P7dR8mSH', title: 'Fabric API', iconUrl: 'https://evil.example/x.png', loaders: ['fabric', 'quilt'] },
        ],
      },
    ],
    conflicts: [
      { a: { source: 'modrinth', projectId: 'AANobbMI', title: 'Sodium' }, b: { source: 'modrinth', projectId: 'sk9rgfiA', title: 'Embeddium' }, reason: 'renderer' },
      { a: { source: 'modrinth', projectId: 'k2ZPuTBm', title: 'Essential' }, b: null, reason: 'trsClient' },
    ],
  })

  it('shows deps that are not in the preset yet', () => {
    const deps = depsOf(check, items[0]!, items)
    expect(deps.map((d) => d.title)).toEqual(['Fabric API'])
    // Fremde Symbol-Adressen hat das Schema schon entfernt.
    expect(deps[0]!.iconUrl).toBeNull()
    expect(depsOf(check, items[1]!, items)).toEqual([])
    expect(depsOf(null, items[0]!, items)).toEqual([])
  })

  it('finds conflicts for both sides and words them', () => {
    expect(conflictsOf(check, items[1]!)).toHaveLength(1)
    expect(conflictsOf(check, items[2]!)).toHaveLength(1)
    expect(conflictsOf(check, items[0]!)).toHaveLength(0)
    expect(conflictText(check.conflicts[0]!)).toContain('Sodium')
    expect(conflictText(check.conflicts[0]!)).toContain('Embeddium')
    expect(conflictText(check.conflicts[1]!)).toContain('Essential')
  })

  it('pick list schema keeps only safe icons', () => {
    const list = presetPickListSchema.parse({
      name: 'Pack',
      gameVersion: '1.21.1',
      loader: 'fabric',
      items: [{ ...sodium, iconUrl: 'javascript:alert(1)' }, { ...faithful, iconUrl: 'https://cdn.modrinth.com/data/BdYrfSSx/icon.png' }],
    })
    expect(list.items[0]!.iconUrl).toBeNull()
    expect(list.items[1]!.iconUrl).toBe('https://cdn.modrinth.com/data/BdYrfSSx/icon.png')
    expect(presetPickListSchema.safeParse({ name: 'x', gameVersion: null, loader: 'bukkit', items: [] }).success).toBe(false)
  })
})

describe('helpers', () => {
  it('groups by kind in fixed order and converts search hits', () => {
    const groups = groupPresetItems([item('a', 'A', { kind: 'shaderpack' }), item('b', 'B'), item('c', 'C', { kind: 'resourcepack' }), item('d', 'D')])
    expect(groups.map((g) => [g.kind, g.items.length])).toEqual([
      ['mod', 2],
      ['resourcepack', 1],
      ['shaderpack', 1],
    ])
    expect(simpleName("Xaero's Minimap (Fair-Play)")).toBe('xaerosminimapfairplay')
    const hit = { projectId: '238222', slug: 'jei', title: '', description: '', author: 'mezz', iconUrl: 'https://media.forgecdn.net/x.png', downloads: 1, follows: 0, categories: [], clientSide: 'required', serverSide: 'optional', dateModified: null, license: null } as ModrinthHit
    expect(hitToItem(hit, 'curseforge', 'mod')).toEqual({ source: 'curseforge', projectId: '238222', title: 'jei', iconUrl: 'https://media.forgecdn.net/x.png', kind: 'mod' })
  })
})
