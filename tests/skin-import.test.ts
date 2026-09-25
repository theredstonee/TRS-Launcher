import { beforeAll, describe, expect, it } from 'vitest'
import type { SkinImportCandidate } from '../app/types'
import { setLocale } from '../app/utils/i18n'
import { firstIssue, skinUrlSchema } from '../app/utils/schemas'
import {
  bulkPlan,
  defaultSelection,
  groupBySource,
  isLauncherSource,
  selectedRequests,
  unusedTokens,
} from '../app/utils/skinImport'

// Dialog-Logik von „Skin hinzufügen“. Die eigentliche Prüfung (PNG, Größe,
// Link-Schutz) macht der Kern – siehe skin_import.rs.

beforeAll(() => setLocale('de'))

function candidate(token: string, extra: Partial<SkinImportCandidate> = {}): SkinImportCandidate {
  return {
    token,
    name: token,
    variant: 'classic',
    texture: 'data:image/png;base64,AAAA',
    source: 'minecraft',
    duplicate: false,
    ...extra,
  }
}

describe('Skins aus anderen Launchern', () => {
  const list = [
    candidate('m1'),
    candidate('p1', { source: 'prism' }),
    candidate('mr1', { source: 'modrinth', duplicate: true }),
    candidate('m2', { duplicate: true }),
    candidate('f1', { source: 'file' }),
  ]

  it('gruppiert nach Launcher in fester Reihenfolge', () => {
    const groups = groupBySource(list)
    expect(groups.map((g) => g.source)).toEqual(['minecraft', 'prism', 'modrinth'])
    expect(groups[0]!.items.map((c) => c.token)).toEqual(['m1', 'm2'])
    expect(groupBySource([])).toEqual([])
  })

  it('wählt vor, was noch nicht in der Sammlung liegt', () => {
    expect([...defaultSelection(list)]).toEqual(['m1', 'p1', 'f1'])
  })

  it('übernimmt nur Markiertes und verwirft den Rest', () => {
    const requests = selectedRequests(list, new Set(['p1', 'm2']))
    expect(requests).toEqual([{ token: 'p1' }, { token: 'm2' }])
    expect(unusedTokens(list, requests.map((r) => r.token))).toEqual(['m1', 'mr1', 'f1'])
  })

  it('kennt nur echte Launcher als Quelle', () => {
    expect(isLauncherSource('prism')).toBe(true)
    expect(isLauncherSource('file')).toBe(false)
  })
})

describe('mehrere Dateien auf einmal', () => {
  it('übernimmt Dateiname + erkanntes Modell und überspringt Doppelte', () => {
    const plan = bulkPlan([candidate('a'), candidate('b', { duplicate: true }), candidate('c', { variant: 'slim' })])
    // Kein Name/Modell mitgeschickt → der Kern nimmt Vorschlag und Erkennung.
    expect(plan.requests).toEqual([{ token: 'a' }, { token: 'c' }])
    expect(plan.skipped).toEqual(['b'])
  })
})

describe('skinUrlSchema', () => {
  it('nimmt https-Links an', () => {
    expect(skinUrlSchema.parse('  https://example.com/skins/a.png ')).toBe('https://example.com/skins/a.png')
  })

  it('lehnt alles andere früh ab', async () => {
    for (const bad of ['', 'http://example.com/a.png', 'javascript:alert(1)', 'file:///C:/x.png', 'https://u:p@example.com/a.png', 'kein link']) {
      expect(skinUrlSchema.safeParse(bad).success, bad).toBe(false)
    }
    const result = skinUrlSchema.safeParse('http://example.com/a.png')
    if (!result.success) expect(firstIssue(result.error)).toContain('https')
    expect(skinUrlSchema.safeParse(`https://example.com/${'a'.repeat(2100)}`).success).toBe(false)
  })
})
