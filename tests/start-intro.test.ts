import { describe, expect, it } from 'vitest'
import { DEFAULT_INTRO_DESIGN, INTRO_DESIGNS, introDesignById, pickIntroDesign } from '../app/utils/intro/designs'
import { FROST_CUES } from '../app/utils/intro/frost'
import { GRAVEYARD_CUES, LANTERNS } from '../app/utils/intro/graveyard'
import {
  introDay,
  introReduced,
  parseIntroMemory,
  parseIntroPrefs,
  pickIntroLength,
  shouldPlayIntro,
} from '../app/utils/intro/select'
import { planDungeon, planFrost, planGraveyard, type SfxEvent } from '../app/utils/intro/sfx'
import { buildScript, doorsAfter, INTRO_CUES, INTRO_LENGTHS, WORLD, wireHead, wireTimeAt, type IntroCoreCues } from '../app/utils/intro/timeline'

const today = '2026-10-21'

describe('Start-Animation: welche Länge', () => {
  it('nach einem Update und beim allerersten Start lang', () => {
    expect(pickIntroLength({ last: null, today, version: '0.27.0' })).toBe('long')
    expect(pickIntroLength({ last: { day: today, version: '0.26.0' }, today, version: '0.27.0' })).toBe('long')
  })

  it('erster Start des Tages mittel, sonst kurz', () => {
    expect(pickIntroLength({ last: { day: '2026-10-20', version: '0.27.0' }, today, version: '0.27.0' })).toBe('medium')
    expect(pickIntroLength({ last: { day: today, version: '0.27.0' }, today, version: '0.27.0' })).toBe('short')
  })

  it('unbekannte Version (Browser) zählt nur den Tag; Handys immer kurz', () => {
    expect(pickIntroLength({ last: { day: today, version: '' }, today, version: '' })).toBe('short')
    expect(pickIntroLength({ last: { day: today, version: '0.26.0' }, today, version: '' })).toBe('short')
    expect(pickIntroLength({ last: null, today, version: '0.27.0', mobile: true })).toBe('short')
  })

  it('die Update-Fassung läuft immer, kurz und mittel lassen sich abschalten', () => {
    expect(shouldPlayIntro('long', false)).toBe(true)
    expect(shouldPlayIntro('medium', false)).toBe(false)
    expect(shouldPlayIntro('short', false)).toBe(false)
    expect(shouldPlayIntro('short', true)).toBe(true)
  })

  it('Merker sind robust gegen Müll', () => {
    expect(parseIntroMemory(null)).toBeNull()
    expect(parseIntroMemory('{kaputt')).toBeNull()
    expect(parseIntroMemory('{"day":1}')).toBeNull()
    expect(parseIntroMemory('{"day":"2026-10-21","version":"0.26.0"}')).toEqual({ day: '2026-10-21', version: '0.26.0' })
    expect(parseIntroPrefs(null)).toEqual({ enabled: true, sound: true, motion: 'full' })
    expect(parseIntroPrefs('{"enabled":false,"sound":false,"motion":"reduced"}')).toEqual({ enabled: false, sound: false, motion: 'reduced' })
    expect(parseIntroPrefs('nope').enabled).toBe(true)
  })

  it('lokaler Kalendertag und reduzierte Bewegung', () => {
    expect(introDay(new Date(2026, 0, 5, 23, 59))).toBe('2026-01-05')
    expect(introReduced('reduced', false)).toBe(true)
    expect(introReduced('system', true)).toBe(true)
    expect(introReduced('system', false)).toBe(false)
    expect(introReduced('full', true)).toBe(false)
  })
})

describe('Start-Animation: Design-Registry', () => {
  it('Event hat Vorrang vor dem Design, Redstone ist der Rückfall', () => {
    expect(pickIntroDesign({ events: ['halloween'], design: 'winter' }).id).toBe('halloween')
    expect(pickIntroDesign({ events: [], design: 'winter' }).id).toBe('winter')
    expect(pickIntroDesign({ events: ['unbekannt'], design: 'redstone' }).id).toBe('redstone')
    expect(pickIntroDesign({}).id).toBe(DEFAULT_INTRO_DESIGN.id)
    expect(introDesignById('gibt-es-nicht').id).toBe('redstone')
  })

  it('jede Variante hat eine eindeutige ID und ein eigenes Szenen-Set', () => {
    const ids = INTRO_DESIGNS.map((d) => d.id)
    expect(new Set(ids).size).toBe(ids.length)
    expect(Object.fromEntries(INTRO_DESIGNS.map((d) => [d.id, d.scene]))).toEqual({ halloween: 'graveyard', winter: 'frost', redstone: 'dungeon' })
    for (const d of INTRO_DESIGNS) expect(d.audio.jingleHit).toBeGreaterThan(0)
  })
})

function checkCore(c: IntroCoreCues, duration: number) {
  expect(c.duration).toBe(duration)
  expect(c.fadeIn).toBeGreaterThan(0)
  expect(c.hush).toBeLessThan(c.impact)
  expect(c.impact).toBeLessThan(c.logo)
  expect(c.logo).toBeLessThanOrEqual(c.wordmark)
  expect(c.wordmark).toBeLessThan(c.fadeOut)
  expect(c.fadeOut).toBeLessThan(c.duration)
}

function checkPlan(plan: SfxEvent[], c: IntroCoreCues) {
  for (let i = 1; i < plan.length; i++) expect(plan[i]!.t).toBeGreaterThanOrEqual(plan[i - 1]!.t)
  for (const e of plan) expect(e.t).toBeGreaterThanOrEqual(-0.0001)
  expect(plan.some((e) => e.kind === 'subHit' && e.t === c.impact)).toBe(true)
}

const DURATIONS = { short: 4, medium: 7, long: 10 } as const

describe('Start-Animation: Zeitpläne', () => {
  it('Redstone-Dungeon: Impuls läuft durch alle Türen bis zur Lampe', () => {
    for (const len of INTRO_LENGTHS) {
      const c = INTRO_CUES[len]
      checkCore(c, DURATIONS[len])
      const s = buildScript(len)
      expect(c.doors.length).toBe(doorsAfter(c.from).length)
      for (let i = 1; i < s.wire.length; i++) {
        expect(s.wire[i]!.t).toBeGreaterThanOrEqual(s.wire[i - 1]!.t)
        expect(s.wire[i]!.d).toBeGreaterThanOrEqual(s.wire[i - 1]!.d)
      }
      expect(wireHead(s.wire, c.hush)).toBeCloseTo(WORLD.lamp - 0.5)
      for (const d of s.doors) expect(wireHead(s.wire, d.t)).toBeCloseTo(d.d - 0.5)
      for (const r of s.repeaters) expect(wireTimeAt(s.wire, r.d)).toBeCloseTo(r.t)
      expect(c.wireStart).toBeLessThan(c.doors[0] ?? c.hush)
      checkPlan(planDungeon(s), c)
    }
    // Lang: drei Türen, Auftritt in der Nische; kurz: nur der Höhepunkt
    expect(buildScript('long').doors).toHaveLength(3)
    expect(buildScript('long').cameo).not.toBeNull()
    expect(buildScript('short').doors).toHaveLength(1)
  })

  it('Mondnacht (Halloween): Laternen gültig und vor dem Schlag', () => {
    for (const len of INTRO_LENGTHS) {
      const c = GRAVEYARD_CUES[len]
      checkCore(c, DURATIONS[len])
      for (const [t, idx] of c.lanterns) {
        expect(idx).toBeGreaterThanOrEqual(0)
        expect(idx).toBeLessThan(LANTERNS.length)
        expect(t).toBeLessThan(c.hush)
      }
      for (let i = 1; i < c.cam.length; i++) expect(c.cam[i]![0]).toBeGreaterThan(c.cam[i - 1]![0])
      checkPlan(planGraveyard(c), c)
    }
  })

  it('Winternacht: Kristallschübe vor dem Schlag', () => {
    for (const len of INTRO_LENGTHS) {
      const c = FROST_CUES[len]
      checkCore(c, DURATIONS[len])
      for (const t of c.crystals) expect(t).toBeLessThan(c.hush)
      for (let i = 1; i < c.cam.length; i++) expect(c.cam[i]![0]).toBeGreaterThan(c.cam[i - 1]![0])
      checkPlan(planFrost(c), c)
    }
  })

  it('alle Szenen-Sets zünden zur selben Zeit (ein Jingle-Schnitt je Länge)', () => {
    for (const len of INTRO_LENGTHS) {
      expect(GRAVEYARD_CUES[len].impact).toBe(INTRO_CUES[len].impact)
      expect(FROST_CUES[len].impact).toBe(INTRO_CUES[len].impact)
    }
  })
})
