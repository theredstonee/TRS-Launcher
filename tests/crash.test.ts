import { afterAll, describe, expect, it } from 'vitest'
import type { CrashAnalysis, CrashFinding } from '../app/types'
import { setLocale } from '../app/utils/i18n'
import { actionConfirm, actionLabel, allActions, crashCauseLabel, findingKey, findingMods, findingText, findingTitle, formatMb } from '../app/utils/crash'

const finding = (f: Partial<CrashFinding>): CrashFinding => ({ kind: 'unknown', score: 10, mods: [], evidence: [], actions: [], ...f })

// Der echte Fall aus dem Projekt, wie ihn der Kern schickt (gekürzt).
const realCase: CrashAnalysis = {
  id: '20260926-215844-00ab',
  instanceId: 'fabric-1-21-11',
  at: '2026-09-26T21:58:44Z',
  exitCode: -1,
  playSeconds: 12,
  sources: ['crash-reports/crash-2026-09-26_21.58.44-client.txt', 'live'],
  findings: [
    finding({
      kind: 'known_issue',
      variant: 'trsclient_essential',
      score: 100,
      params: { version: '0.9.0', fixed: '0.9.1' },
      mods: ['trsclient', 'essential', 'mixinextras'],
      actions: [{ type: 'updateTrsClient' }, { type: 'disableMods', files: ['Essential-fabric_1-21-11.jar'] }],
    }),
    finding({
      kind: 'mixin_conflict',
      variant: 'transform',
      score: 60,
      params: { target: 'IntegratedServer (class_1132)', names: 'Essential, MixinExtras' },
      mods: ['essential', 'mixinextras'],
      actions: [{ type: 'disableMods', files: ['Essential-fabric_1-21-11.jar'] }],
    }),
  ],
  mods: [
    { id: 'trsclient', name: 'TRS Client', version: '0.9.0', file: 'trsclient.jar', enabled: true },
    { id: 'essential', name: 'Essential', version: '1.3.10.4', file: 'Essential-fabric_1-21-11.jar', enabled: true },
    { id: 'mixinextras', name: 'MixinExtras', version: '0.5.0', enabled: true, bundledIn: 'Essential' },
  ],
  cause: 'Caused by: java.lang.ClassCastException: …',
  excerpt: [],
}

describe('Absturz-Helfer', () => {
  afterAll(() => setLocale('en'))

  it('erklärt den echten Fall und bietet das Update zuerst an', async () => {
    await setLocale('de')
    const primary = realCase.findings[0]!
    expect(findingKey(primary)).toBe('crashHelper.findings.known_issue.trsclient_essential')
    expect(findingTitle(primary)).toBe('TRS Client 0.9.0 verträgt sich nicht mit Essential')
    expect(findingText(primary)).toContain('TRS Client 0.9.1 behebt das')
    expect(findingMods(primary, realCase).map((m) => m.name)).toEqual(['TRS Client', 'Essential', 'MixinExtras'])
    expect(actionLabel(primary.actions[0]!, realCase)).toBe('TRS Client aktualisieren')
    expect(actionLabel(primary.actions[1]!, realCase)).toBe('Essential deaktivieren')
    expect(actionConfirm(primary.actions[1]!, realCase)).toContain('.disabled')
    // Dieselbe Aktion aus zwei Befunden nur einmal.
    expect(allActions(realCase).map((a) => a.action.type)).toEqual(['updateTrsClient', 'disableMods'])
  })

  it('fällt auf allgemeine Texte zurück und lässt keine Platzhalter stehen', async () => {
    await setLocale('en')
    expect(findingKey(finding({ kind: 'graphics_driver', variant: 'something_new' }))).toBe('crashHelper.findings.graphics_driver.default')
    expect(findingKey(finding({ kind: 'missing_dependency', params: { deps: 'Cloth Config' } }))).toBe('crashHelper.findings.missing_dependency.unnamed')
    expect(findingKey(finding({ kind: 'mixin_conflict', variant: 'redirect_conflict' }))).toBe('crashHelper.findings.mixin_conflict.default')
    for (const f of [
      finding({ kind: 'wrong_java', variant: 'too_old' }),
      finding({ kind: 'incompatible_mod' }),
      finding({ kind: 'out_of_memory', variant: 'heap' }),
      finding({ kind: 'unknown', variant: 'suspect' }),
    ]) {
      expect(findingTitle(f)).not.toMatch(/[{}]/)
      expect(findingText(f)).not.toMatch(/[{}]/)
    }
  })

  it('beschriftet Speicher, Java und Ursachen im Verlauf', async () => {
    await setLocale('en')
    expect(formatMb(4096)).toBe('4 GB')
    expect(formatMb(3584)).toBe('3.5 GB')
    expect(formatMb(768)).toBe('768 MB')
    expect(actionLabel({ type: 'setMemory', fromMb: 2048, toMb: 4096 }, realCase)).toBe('Increase RAM to 4 GB')
    expect(actionLabel({ type: 'setMemory', fromMb: 16384, toMb: 4096 }, realCase)).toBe('Set RAM to 4 GB')
    expect(actionLabel({ type: 'switchJava', major: 21 }, realCase)).toBe('Use Java 21')
    expect(actionLabel({ type: 'switchJava', major: null }, realCase)).toBe('Choose Java automatically')
    expect(crashCauseLabel('known_issue')).toBe('Known problem')
    expect(crashCauseLabel('out_of_memory')).toBe('Out of memory')
    expect(crashCauseLabel('exit:1')).toBe('exit:1')
  })
})
