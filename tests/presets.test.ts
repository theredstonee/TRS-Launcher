import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import type { Preset, PresetApplyReport, PresetItemOutcome } from '../app/types'
import { i18n, setLocale } from '../app/utils/i18n'
import {
  defaultPresetSelection,
  movePreset,
  presetDisabled,
  presetHasMods,
  presetName,
  presetPercent,
  presetStage,
  presetSummaryText,
  summarizePresetReport,
  visiblePresets,
} from '../app/utils/presets'
import { presetInputSchema } from '../app/utils/schemas'

function preset(id: string, patch: Partial<Preset> = {}): Preset {
  return { id, name: id, builtin: null, auto: false, modpackSafe: true, available: true, items: [], ...patch }
}

const fps = preset('trs-fps-boost', { builtin: 'fpsBoost', auto: true, modpackSafe: false })
const nvidium = preset('trs-nvidium', { builtin: 'nvidium', available: false, auto: true, modpackSafe: false })
const voice = preset('trs-voice-chat', { builtin: 'voiceChat', auto: true })
const packs = preset('own', {
  auto: true,
  items: [{ source: 'modrinth', projectId: 'abc', title: 'Faithful', iconUrl: null, kind: 'resourcepack' }],
})
const all = [fps, nvidium, voice, packs]

function outcome(title: string, status: PresetItemOutcome['status'], optional = false): PresetItemOutcome {
  return {
    presetId: 'p',
    projectId: title,
    title,
    iconUrl: null,
    kind: 'mod',
    status,
    optional,
    versionNumber: null,
    detail: null,
    error: null,
  }
}

function report(items: PresetItemOutcome[]): PresetApplyReport {
  return { gameVersion: '1.12.2', loader: 'forge', items, files: [], dependencies: 0 }
}

describe('Presets: Auswahl', () => {
  let before: string
  beforeAll(async () => {
    before = i18n.global.locale.value
    await setLocale('de')
  })
  afterAll(async () => {
    await setLocale(before)
  })

  it('wählt „immer automatisch“ vor – ohne Unpassendes', () => {
    expect(defaultPresetSelection(all, { loader: 'fabric', context: 'instance' })).toEqual(['trs-fps-boost', 'trs-voice-chat', 'own'])
    // Vanilla: FPS-Boost kommt über die TRS-Optimierung.
    expect(defaultPresetSelection(all, { loader: 'vanilla', context: 'instance' })).toEqual(['trs-voice-chat', 'own'])
    // Modpacks: nichts, was sich mit den Mods des Packs beißt.
    expect(defaultPresetSelection(all, { loader: 'fabric', context: 'modpack' })).toEqual(['trs-voice-chat', 'own'])
    expect(visiblePresets(all, 'instance').map((p) => p.id)).not.toContain('trs-nvidium')
  })

  it('erkennt Mods und gesperrte Presets', () => {
    expect(presetHasMods(fps)).toBe(true)
    expect(presetHasMods(packs)).toBe(false)
    expect(presetDisabled(fps, 'vanilla')).toBe(true)
    expect(presetDisabled(fps, 'forge')).toBe(false)
    expect(presetName(fps)).toBe('FPS-Boost')
    expect(presetName(packs)).toBe('own')
  })

  it('verschiebt – ausgeblendete Presets bleiben stehen', () => {
    const ids = ['a', 'hidden', 'b', 'c']
    expect(movePreset(ids, 'a', 1, ['a', 'b', 'c'])).toEqual(['b', 'hidden', 'a', 'c'])
    expect(movePreset(ids, 'c', 1, ['a', 'b', 'c'])).toBe(ids)
    expect(movePreset(ids, 'b', -1)).toEqual(['a', 'b', 'hidden', 'c'])
  })
})

describe('Presets: Bericht', () => {
  let before: string
  beforeAll(async () => {
    before = i18n.global.locale.value
    await setLocale('de')
  })
  afterAll(async () => {
    await setLocale(before)
  })

  it('fasst zusammen und nennt den Grund', () => {
    const r = report([
      outcome('Simple Voice Chat', 'installed'),
      outcome('Sodium', 'alreadyInstalled'),
      outcome('Fabric API', 'duplicate'),
      outcome('Flashback / ReplayMod', 'notAvailable'),
      outcome('Lithium', 'notAvailable', true),
    ])
    const s = summarizePresetReport(r)
    expect([s.done, s.total, s.problems.length, s.quiet.length]).toEqual([3, 4, 1, 1])
    expect(presetSummaryText(r)).toBe('3 von 4 installiert – Flashback / ReplayMod gibt es für 1.12.2 (Forge) nicht')
  })

  it('alles geklappt, nichts zu tun, mehrere Probleme', () => {
    expect(presetSummaryText(report([outcome('A', 'installed'), outcome('B', 'installed')]))).toBe('Alle 2 Projekte installiert')
    expect(presetSummaryText(report([outcome('X', 'notAvailable', true)]))).toBe('Nichts zu installieren')
    const many = report([outcome('A', 'installed'), outcome('B', 'needsLoader'), outcome('C', 'failed'), outcome('D', 'failed')])
    expect(presetSummaryText(many)).toBe('1 von 4 installiert – B braucht einen Modloader (+2 weitere)')
  })

  it('Fortschritt: Prüfen 0–20 %, Laden 20–100 %', () => {
    expect(presetPercent({ phase: 'resolve', done: 5, total: 10, title: null })).toBe(10)
    expect(presetPercent({ phase: 'install', done: 0, total: 4, title: 'Sodium' })).toBe(20)
    expect(presetPercent({ phase: 'install', done: 4, total: 4, title: null })).toBe(100)
    expect(presetPercent({ phase: 'install', done: 0, total: 0, title: null })).toBe(20)
    expect(presetStage({ phase: 'install', done: 0, total: 3, title: 'Sodium' })).toBe('Sodium wird geladen (1/3)')
  })
})

describe('Presets: Eingaben', () => {
  const item = { source: 'modrinth', projectId: 'AANobbMI', title: 'Sodium', iconUrl: null, kind: 'mod' } as const

  it('prüft Namen, Einträge und Duplikate', () => {
    expect(presetInputSchema.safeParse({ name: ' Basics ', auto: true, items: [item] }).success).toBe(true)
    expect(presetInputSchema.safeParse({ name: '', auto: false, items: [] }).success).toBe(false)
    expect(presetInputSchema.safeParse({ name: 'x'.repeat(49), auto: false, items: [] }).success).toBe(false)
    expect(presetInputSchema.safeParse({ name: 'A', auto: false, items: [item, item] }).success).toBe(false)
    expect(presetInputSchema.safeParse({ name: 'A', auto: false, items: [{ ...item, projectId: '../x' }] }).success).toBe(false)
    expect(presetInputSchema.safeParse({ name: 'A', auto: false, items: [{ ...item, iconUrl: 'https://evil.example/x.png' }] }).success).toBe(false)
    expect(presetInputSchema.safeParse({ name: 'A', auto: false, items: [{ ...item, source: 'curseforge' }] }).success).toBe(false)
  })
})
