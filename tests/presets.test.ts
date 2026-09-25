import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import type { Preset, PresetApplyReport, PresetItemOutcome } from '../app/types'
import { i18n, setLocale } from '../app/utils/i18n'
import {
  adjustSelectionForLoader,
  defaultPresetSelection,
  isFpsTier,
  isShaderTier,
  loaderHasShaders,
  movePreset,
  presetBlocked,
  presetDisabled,
  presetHasMods,
  presetName,
  presetPercent,
  presetReason,
  presetRows,
  presetStage,
  presetSummaryText,
  selectedFpsTier,
  summarizePresetReport,
  tierPresetId,
  visiblePresets,
  withFpsTier,
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
    compatWith: null,
  }
}

function report(items: PresetItemOutcome[]): PresetApplyReport {
  return { gameVersion: '1.12.2', loader: 'forge', items, files: [], dependencies: 0, shaderPack: null }
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
    expect(presetName(fps)).toBe('FPS-Boost: Max FPS')
    expect(presetName(packs)).toBe('own')
  })
})

describe('Presets: FPS-Boost-Stufen', () => {
  const lite = preset('trs-fps-shader-lite', { builtin: 'fpsShaderLite', modpackSafe: false })
  const pretty = preset('trs-fps-shader', { builtin: 'fpsShader', modpackSafe: false })
  const nv = preset('trs-nvidium', { builtin: 'nvidium', modpackSafe: false })
  const list = [fps, lite, pretty, nv, voice]
  let before: string
  beforeAll(async () => {
    before = i18n.global.locale.value
    await setLocale('de')
  })
  afterAll(async () => {
    await setLocale(before)
  })

  it('genau eine Stufe – Shader nehmen Nvidium heraus', () => {
    expect(selectedFpsTier(list, ['trs-voice-chat'])).toBeNull()
    expect(selectedFpsTier(list, ['trs-voice-chat', 'trs-fps-boost'])).toBe('fpsBoost')

    const shader = withFpsTier(list, ['trs-fps-boost', 'trs-nvidium', 'trs-voice-chat'], 'fpsShader')
    expect(shader.sort()).toEqual(['trs-fps-shader', 'trs-voice-chat'])
    expect(withFpsTier(list, shader, 'fpsShaderLite').sort()).toEqual(['trs-fps-shader-lite', 'trs-voice-chat'])
    expect(withFpsTier(list, shader, null)).toEqual(['trs-voice-chat'])
    // Max FPS verträgt sich mit Nvidium.
    expect(withFpsTier(list, ['trs-nvidium'], 'fpsBoost').sort()).toEqual(['trs-fps-boost', 'trs-nvidium'])

    expect(presetBlocked(nv, list, ['trs-fps-shader'])).toBe(true)
    expect(presetBlocked(nv, list, ['trs-fps-boost'])).toBe(false)
    expect(presetBlocked(voice, list, ['trs-fps-shader'])).toBe(false)
  })

  it('Shader nur mit Iris-Loadern, FPS-Boost nie bei Vanilla', () => {
    expect(loaderHasShaders('fabric') && loaderHasShaders('quilt') && loaderHasShaders('neoforge')).toBe(true)
    expect(loaderHasShaders('forge')).toBe(false)
    expect(presetDisabled(lite, 'forge')).toBe(true)
    expect(presetDisabled(fps, 'forge')).toBe(false)
    expect(presetDisabled(pretty, 'vanilla')).toBe(true)
    expect(isFpsTier('fpsShader') && !isFpsTier('nvidium') && isShaderTier('fpsShaderLite') && !isShaderTier('fpsBoost')).toBe(true)

    // Wechsel auf Forge: Shader-Stufe wird zu Max FPS, auf Vanilla fällt alles weg.
    expect(adjustSelectionForLoader(list, ['trs-fps-shader', 'trs-voice-chat'], 'forge').sort()).toEqual(['trs-fps-boost', 'trs-voice-chat'])
    expect(adjustSelectionForLoader(list, ['trs-fps-shader', 'trs-voice-chat'], 'vanilla')).toEqual(['trs-voice-chat'])
    const same = ['trs-fps-shader']
    expect(adjustSelectionForLoader(list, same, 'fabric')).toBe(same)
  })

  it('Vorauswahl: höchstens eine automatische Stufe', () => {
    const autoLite = [fps, { ...lite, auto: true }, { ...nv, auto: true }, voice]
    // Die Liste hat versehentlich zwei automatische Stufen: die erste gewinnt (Max FPS verträgt Nvidium).
    expect(defaultPresetSelection(autoLite, { loader: 'fabric', context: 'instance' })).toEqual(['trs-fps-boost', 'trs-nvidium', 'trs-voice-chat'])
    // Shader-Stufe automatisch: Nvidium fällt weg.
    const shaderFirst = [{ ...lite, auto: true }, { ...nv, auto: true }, voice]
    expect(defaultPresetSelection(shaderFirst, { loader: 'fabric', context: 'instance' })).toEqual(['trs-fps-shader-lite', 'trs-voice-chat'])
    const onlyLite = [{ ...fps, auto: false }, { ...lite, auto: true }, voice]
    expect(defaultPresetSelection(onlyLite, { loader: 'fabric', context: 'instance' })).toEqual(['trs-fps-shader-lite', 'trs-voice-chat'])
    // Forge: statt Shader leicht eben Max FPS.
    expect(defaultPresetSelection(onlyLite, { loader: 'forge', context: 'instance' })).toEqual(['trs-voice-chat', 'trs-fps-boost'])
  })

  it('zeigt die Stufen als eine Zeile', () => {
    const rows = presetRows([voice, fps, lite, pretty, packs])
    expect(rows.map((r) => (r.type === 'fps' ? `fps:${r.tiers.length}` : r.preset.id))).toEqual(['trs-voice-chat', 'fps:3', 'own'])
    expect(tierPresetId(list, 'fpsShader')).toBe('trs-fps-shader')
    expect(presetName(lite)).toBe('FPS-Boost: Shader leicht')
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

  it('getauschte Mods zählen als erledigt und nennen den Grund', () => {
    const swapped = { ...outcome('Sodium', 'swapped'), versionNumber: 'mc1.21.11-0.8.12-fabric', compatWith: 'Iris 1.10.7+mc1.21.11' }
    const r = report([swapped, outcome('Iris', 'alreadyInstalled')])
    expect(summarizePresetReport(r).problems).toEqual([])
    expect(presetSummaryText(r)).toBe('Alle 2 Projekte installiert')
    expect(presetReason(swapped, r)).toBe('Gegen eine Version getauscht, die mit Iris 1.10.7+mc1.21.11 läuft')
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
