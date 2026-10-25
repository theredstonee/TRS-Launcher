import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { reactive } from 'vue'
import { creatorEventSchemas, creatorSelfSchema, fanCapeShare, visibleTabs } from '../app/utils/creator'
import {
  creatorStatsDetailSchema,
  creatorStatsListSchema,
  filterRows,
  lineSegments,
  niceMax,
  nextSort,
  percent,
  seriesSummary,
  sortRows,
  teamActionKey,
  teamActions,
  type CreatorStatRow,
} from '../app/utils/creatorStats'
import { hasKey, setLocale } from '../app/utils/i18n'
import { mayOpen, myTeamSchema, visibleSections } from '../app/utils/teamAccess'

// Creator-Statistik (API §35.14), Team-Modus (§35.9) und der Fan-Umhang-Anteil (nie über 100 %).

const A = 'b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0'
const B = 'c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0'
const C = 'd0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0'

function row(uuid: string, name: string, over: Partial<{ fans: number; d7: number; players: number; online: number; tier: string; state: string; code: string }> = {}): CreatorStatRow {
  return {
    uuid, name, tier: (over.tier ?? 'partner') as CreatorStatRow['tier'], state: (over.state ?? 'active') as CreatorStatRow['state'],
    since: '2026-10-03T00:00:00.000Z', mainCode: over.code ?? name.toUpperCase(),
    fans: { total: over.fans ?? 0, today: 0, d7: over.d7 ?? 0, d30: 0 },
    newPlayers: { total: over.players ?? 0, d7: 0, d30: 0 },
    pets: { pet: null, codes: 0, activeCodes: 0, redemptions: 0, month: { month: '2026-10', used: 0, limit: 50, redemptions: 0 } },
    activity: { online7d: over.online ?? 0, online30d: 0, played7d: 0, played30d: 0 },
  }
}

describe('Rangliste', () => {
  const rows = [
    row(A, 'Pixelmaus', { fans: 842, d7: 5, players: 10, online: 300, tier: 'top' }),
    row(B, 'Redfox', { fans: 120, d7: 51, players: 40, online: 90, code: 'RED-FOX' }),
    row(C, 'alpha', { fans: 120, d7: 1, players: 0, online: 0, tier: 'starter', state: 'revoked' }),
  ]

  it('sortiert nach jeder Kennzahl, Gleichstand nach Name', () => {
    expect(sortRows(rows, 'fans', 'desc').map((r) => r.name)).toEqual(['Pixelmaus', 'alpha', 'Redfox'])
    expect(sortRows(rows, 'fans7d', 'desc').map((r) => r.name)).toEqual(['Redfox', 'Pixelmaus', 'alpha'])
    expect(sortRows(rows, 'newPlayers', 'asc').map((r) => r.name)).toEqual(['alpha', 'Pixelmaus', 'Redfox'])
    expect(sortRows(rows, 'name', 'asc').map((r) => r.name)).toEqual(['alpha', 'Pixelmaus', 'Redfox'])
    expect(rows[0]!.name).toBe('Pixelmaus')
  })

  it('dreht die Richtung beim zweiten Klick, neue Spalte beginnt passend', () => {
    expect(nextSort({ key: 'fans', order: 'desc' }, 'fans')).toEqual({ key: 'fans', order: 'asc' })
    expect(nextSort({ key: 'fans', order: 'asc' }, 'online7d')).toEqual({ key: 'online7d', order: 'desc' })
    expect(nextSort({ key: 'fans', order: 'desc' }, 'name')).toEqual({ key: 'name', order: 'asc' })
  })

  it('filtert nach Name, UUID, Code (ohne Bindestrich), Stufe und Zustand', () => {
    expect(filterRows(rows, { q: 'pix' }).map((r) => r.uuid)).toEqual([A])
    expect(filterRows(rows, { q: 'redfox' }).map((r) => r.uuid)).toEqual([B])
    expect(filterRows(rows, { q: 'd0d0' }).map((r) => r.uuid)).toEqual([C])
    expect(filterRows(rows, { tier: 'top' }).map((r) => r.uuid)).toEqual([A])
    expect(filterRows(rows, { state: 'revoked' }).map((r) => r.uuid)).toEqual([C])
    expect(filterRows(rows, { tier: 'all', state: 'all' })).toHaveLength(3)
  })

  it('liest die Antworten des Kerns', () => {
    const list = creatorStatsListSchema.parse({
      generatedAt: null,
      definitions: { newPlayerWindowHours: 24, activitySince: null },
      totals: { creators: 1, fans: 1, fans7d: 0, fans30d: 0, newPlayers: 0, newPlayers30d: 0, petRedemptions: 0, online7d: 0, online30d: 0 },
      creators: [rows[0]],
    })
    expect(list.creators[0]!.tier).toBe('top')
    const detail = creatorStatsDetailSchema.parse({
      generatedAt: '2026-10-25T12:00:00.000Z',
      definitions: { newPlayerWindowHours: 24, activitySince: '2026-10-25' },
      creator: rows[1],
      fanCape: { holders: 6, fanHolders: 4 },
      series: [{ day: '2026-10-25', newFans: 1, newPlayers: 0, redemptions: 1, petRedemptions: 0, activeFans: null, playingFans: null }],
    })
    expect(detail.series[0]!.activeFans).toBeNull()
  })
})

describe('Diagramme', () => {
  it('runden die Achse auf schöne Werte', () => {
    expect(niceMax(0)).toBe(1)
    expect(niceMax(7)).toBe(10)
    expect(niceMax(18)).toBe(20)
    expect(niceMax(230)).toBe(250)
  })

  it('überbrücken keine Lücken (nicht erfasste Tage)', () => {
    const pts = [
      { day: '2026-10-20', value: null },
      { day: '2026-10-21', value: 4 },
      { day: '2026-10-22', value: 2 },
      { day: '2026-10-23', value: null },
      { day: '2026-10-24', value: 5 },
    ]
    const segs = lineSegments(pts, 400, 100, 0)
    expect(segs).toHaveLength(2)
    expect(segs[0]!.startsWith('M100.0')).toBe(true)
    expect(segs[0]!.split('L')).toHaveLength(2)
    expect(seriesSummary(pts)).toEqual({ total: 11, peak: 5, known: 3 })
    expect(lineSegments([], 400, 100, 0)).toEqual([])
  })
})

describe('Fan-Umhang-Anteil', () => {
  it('geht nie über 100 % – ehemalige Fans zählen nicht mehr', () => {
    // Fehlerfall: 6 Besitzer bei 4 aktiven Fans ergab früher 150 %.
    expect(fanCapeShare({ activeFans: 4, fanCapeHolders: 6, fanCapeFans: 3 })).toEqual({ held: 3, fans: 4, percent: 75 })
    expect(fanCapeShare({ activeFans: 4, fanCapeHolders: 6, fanCapeFans: null })).toEqual({ held: 4, fans: 4, percent: 100 })
    expect(fanCapeShare({ activeFans: 0, fanCapeHolders: 2, fanCapeFans: 0 }).percent).toBe(0)
    expect(percent(9, 4)).toBe(100)
  })
})

describe('Team-Modus', () => {
  const base = {
    uuid: A, name: 'Pixelmaus', tier: 'top', state: 'active', reason: null, since: null, channels: [],
    codes: [], limits: { extraCodes: null, maxUsesPerCode: null, symbolFrames: 8, fanCape: 'hd' },
    stats: { activeFans: 4, redemptions: 4, fanCapeHolders: 6, newPlayers: 1, packInstalls: 0, daily: [] },
    milestone: null, nextMilestone: null,
    symbol: { current: null, pending: null, rejected: null }, fanCape: { current: null, pending: null, rejected: null },
    requests: [], packs: [],
  }

  it('liest viewer.team, teamChanges und fanCapeFans (alte Antworten ohne die Felder gehen weiter)', () => {
    const old = creatorSelfSchema.parse({ ...base, viewer: { helper: true, rights: ['stats'] } })
    expect(old.viewer).toEqual({ helper: true, team: false, rights: ['stats'] })
    expect(old.teamChanges).toEqual([])
    expect(old.stats.fanCapeFans).toBeNull()
    const team = creatorSelfSchema.parse({
      ...base,
      stats: { ...base.stats, fanCapeFans: 3 },
      viewer: { helper: false, team: true, rights: ['stats', 'codes', 'uploads', 'packs'] },
      teamChanges: [{ action: 'pet.code.create', at: '2026-10-25T09:00:00.000Z' }],
    })
    expect(visibleTabs(team.viewer)).toContain('helpers')
    expect(visibleTabs(team.viewer)).toContain('tier')
    expect(team.teamChanges[0]!.action).toBe('pet.code.create')
  })

  it('kennt das Ereignis team_changed mit Aktion', () => {
    const [updated] = creatorEventSchemas
    const e = updated.parse({ type: 'creator_updated', kind: 'team_changed', reason: null, tier: null, milestone: null, helper: null, action: 'code.create' })
    expect(e.action).toBe('code.create')
    expect(updated.parse({ type: 'creator_updated', kind: 'tier_changed', reason: null, tier: 'top', milestone: null, helper: null }).action).toBeNull()
  })

  it('hat Texte für alle Aktionen in allen Sprachen, unbekannte → allgemein', async () => {
    for (const locale of ['en', 'de', 'es', 'fr', 'nl', 'pl', 'pt-BR', 'tr'] as const) {
      await setLocale(locale)
      for (const a of teamActions) expect(hasKey(teamActionKey(a)), `${locale}: ${a}`).toBe(true)
      expect(hasKey('team.creatorStats.detail.charts.active')).toBe(true)
      expect(hasKey('creator.teamMode.bannerTitle')).toBe(true)
    }
    await setLocale('de')
    expect(teamActionKey('profile.submit')).toBe('creator.teamMode.actions.profile_submit')
    expect(teamActionKey('nuke.all')).toBe('creator.teamMode.actions.other')
  })

  it('Bereich Statistik nur mit creators.manage', () => {
    const manage = myTeamSchema.parse({ rank: 400, roles: [{ id: 'content' }], permissions: ['creators.review', 'creators.manage'], limits: {} })
    const review = myTeamSchema.parse({ rank: 700, roles: [{ id: 'senior_moderator' }], permissions: ['creators.review'], limits: {} })
    expect(visibleSections(manage).map((s) => s.to)).toContain('/admin/creator-stats')
    expect(mayOpen(manage, `/admin/creator-stats/${A}`)).toBe(true)
    expect(mayOpen(review, '/admin/creator-stats')).toBe(false)
    expect(mayOpen(review, '/admin/creators')).toBe(true)
  })
})

describe('Store im Team-Modus', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetModules()
  })

  it('behält actingAs für Team-Mitglieder, auch ohne Helfer-Eintrag', async () => {
    const calls: { cmd: string; args: unknown }[] = []
    const self = { ...{
      uuid: B, name: 'Redfox', tier: 'partner', state: 'active', reason: null, since: null, channels: [], codes: [],
      limits: { extraCodes: 5, maxUsesPerCode: 1000, symbolFrames: 8, fanCape: 'standard' },
      stats: { activeFans: 1, redemptions: 1, fanCapeHolders: 0, newPlayers: 0, packInstalls: 0, daily: [] },
      milestone: null, nextMilestone: null, symbol: { current: null, pending: null, rejected: null },
      fanCape: { current: null, pending: null, rejected: null }, requests: [], packs: [], helpers: [] },
      viewer: { helper: false, team: true, rights: ['stats', 'codes', 'uploads', 'packs'] }, teamChanges: [] }
    vi.doMock('../app/utils/backend', () => ({
      backend: {
        creator: {
          me: async () => ({ support: { creator: null, startedAt: null, lastCreatorUuid: null, switchAvailableAt: null }, fanCapes: [], blocked: [], showFanSymbols: true, creator: null, application: null, helping: [], helperInvites: [] }),
          dashboard: async (as: string | null) => {
            calls.push({ cmd: 'dashboard', args: as })
            return self
          },
          removeHelper: async (uuid: string, as: string | null) => {
            calls.push({ cmd: 'removeHelper', args: [uuid, as] })
          },
        },
      },
      errorMessage: (e: unknown) => String(e),
    }))
    const team = myTeamSchema.parse({ rank: 900, roles: [{ id: 'admin' }], permissions: ['creators.manage'], limits: {} })
    const trs = reactive<{ enabled: boolean; status: { account: string }; team: unknown }>({ enabled: true, status: { account: A }, team })
    vi.stubGlobal('useTrsStore', () => trs)
    const { useCreatorStore } = await import('../app/stores/creator')
    const store = useCreatorStore()
    await store.actAs(B)
    await store.load()
    expect(store.actingAs).toBe(B)
    expect(store.teamMode).toBe(true)
    expect(store.canTeamMode).toBe(true)
    await store.removeHelper(C)
    expect(calls).toContainEqual({ cmd: 'removeHelper', args: [C, B] })

    // Ohne Team-Recht fällt man zurück auf das eigene Dashboard.
    trs.team = null
    await store.load()
    expect(store.actingAs).toBeNull()
    vi.unstubAllGlobals()
  })
})
