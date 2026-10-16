import { describe, expect, it } from 'vitest'
import { rgba } from '../app/utils/capeImage'
import {
  buildSymbolStrip,
  canAddCode,
  canInviteHelper,
  canSupport,
  centerSquare,
  cleanCodeInput,
  codeShares,
  codeStatus,
  creatorEventSchemas,
  creatorSelfSchema,
  expiryFromDate,
  invitableFriends,
  isCreatorCode,
  milestoneProgress,
  myCreatorSchema,
  nextTier,
  normalizeCreatorCode,
  recentSum,
  supportDays,
  switchState,
  symbolFrameMs,
  symbolSize,
  symbolVisible,
  tierAtLeast,
  visibleTabs,
} from '../app/utils/creator'

const DAY = 86_400_000
const NOW = Date.parse('2026-10-16T12:00:00.000Z')
const A = 'b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0'
const B = 'c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0'

describe('Creator-Codes', () => {
  it('folgen dem Muster der API', () => {
    expect(normalizeCreatorCode(' trs-pixel ')).toBe('TRS-PIXEL')
    expect(normalizeCreatorCode('PIXEL')).toBe('PIXEL')
    expect(normalizeCreatorCode('ABCD')).toBeNull()
    expect(normalizeCreatorCode('A'.repeat(21))).toBeNull()
    expect(normalizeCreatorCode('-PIXEL')).toBeNull()
    expect(normalizeCreatorCode('PIX EL')).toBeNull()
  })

  it('gehen nur mit Bindestrich ins Creator-Einlösen, sonst ist es ein Umhang-Code', () => {
    expect(isCreatorCode('trs-pixel')).toBe(true)
    expect(isCreatorCode('PIXELMAUS')).toBe(true)
    expect(isCreatorCode('7K3QF-M2XPA-9RTVB-C4HJN')).toBe(false)
    expect(isCreatorCode('7K3QFM2XPA9RTVBC4HJN')).toBe(false)
  })

  it('werden beim Tippen bereinigt', () => {
    expect(cleanCodeInput('trs_pixel!-yt')).toBe('TRSPIXEL-YT')
    expect(cleanCodeInput('a'.repeat(30))).toHaveLength(20)
  })

  it('haben einen Status und zählen gegen die Grenze nur eingeschaltet', () => {
    const base = { active: true, uses: 0, maxUses: null, expiresAt: null }
    expect(codeStatus(base, NOW)).toBe('active')
    expect(codeStatus({ ...base, active: false }, NOW)).toBe('off')
    expect(codeStatus({ ...base, uses: 100, maxUses: 100 }, NOW)).toBe('usedUp')
    expect(codeStatus({ ...base, expiresAt: '2026-10-01T00:00:00.000Z' }, NOW)).toBe('expired')
    const codes = [
      { kind: 'main' as const, active: true },
      { kind: 'extra' as const, active: false },
    ]
    expect(canAddCode(codes, { extraCodes: 1 })).toBe(true)
    expect(canAddCode([...codes, { kind: 'extra', active: true }], { extraCodes: 1 })).toBe(false)
    expect(canAddCode([...codes, { kind: 'extra', active: true }], { extraCodes: null })).toBe(true)
    expect(codeShares([{ code: 'A-AAAA', uses: 3 }, { code: 'B-BBBB', uses: 1 }]).map((c) => c.percent)).toEqual([75, 25])
    expect(codeShares([{ code: 'A-AAAA', uses: 0 }])[0]!.percent).toBe(0)
    expect(expiryFromDate('2026-10-31')).toMatch(/^2026-1[01]-\d\dT/)
    expect(expiryFromDate('31.10.2026')).toBeNull()
  })
})

describe('30-Tage-Regel', () => {
  it('sperrt einen anderen Creator bis zum Wechsel-Zeitpunkt', () => {
    const support = { startedAt: new Date(NOW - 10 * DAY).toISOString(), lastCreatorUuid: A, switchAvailableAt: new Date(NOW + 20 * DAY).toISOString() }
    expect(switchState(support, NOW)).toMatchObject({ locked: true, daysLeft: 20 })
    // Bruchteile eines Tages zählen als ganzer Tag.
    expect(switchState({ ...support, switchAvailableAt: new Date(NOW + 2 * 3600_000).toISOString() }, NOW).daysLeft).toBe(1)
    expect(switchState({ ...support, switchAvailableAt: new Date(NOW - 1).toISOString() }, NOW)).toEqual({ locked: false, daysLeft: 0, at: null })
  })

  it('rechnet ohne Angabe des Servers 30 Tage ab dem Start', () => {
    const support = { startedAt: new Date(NOW - 25 * DAY).toISOString(), lastCreatorUuid: A, switchAvailableAt: null }
    expect(switchState(support, NOW).daysLeft).toBe(5)
    expect(switchState({ startedAt: null, lastCreatorUuid: null, switchAvailableAt: null }, NOW).locked).toBe(false)
    expect(supportDays(support.startedAt, NOW)).toBe(25)
    expect(supportDays(null, NOW)).toBe(0)
  })

  it('erlaubt denselben Creator immer, einen anderen erst nach Ablauf', () => {
    const support = { creator: null, startedAt: new Date(NOW - DAY).toISOString(), lastCreatorUuid: A, switchAvailableAt: new Date(NOW + 29 * DAY).toISOString() }
    expect(canSupport(support, A, NOW)).toBe(true)
    expect(canSupport(support, B, NOW)).toBe(false)
    expect(canSupport(support, B, NOW + 30 * DAY)).toBe(true)
  })
})

describe('Stufen, Statistik, Helfer', () => {
  it('Stufen und Meilensteine', () => {
    expect(tierAtLeast('partner', 'starter')).toBe(true)
    expect(tierAtLeast('starter', 'partner')).toBe(false)
    expect(nextTier('starter')).toBe('partner')
    expect(nextTier('top')).toBeNull()
    expect(milestoneProgress(300, { at: 500 }, 'bronze')).toBe(50)
    expect(milestoneProgress(50, { at: 100 }, null)).toBe(50)
    expect(milestoneProgress(9000, null, 'diamond')).toBe(100)
    const daily = Array.from({ length: 10 }, (_, i) => ({ day: `2026-10-${String(i + 1).padStart(2, '0')}`, newFans: i, redemptions: 1 }))
    expect(recentSum(daily, 'newFans')).toBe(3 + 4 + 5 + 6 + 7 + 8 + 9)
    expect(recentSum(daily, 'redemptions', 30)).toBe(10)
  })

  it('Helfer sehen nur die Reiter ihrer Rechte', () => {
    expect(visibleTabs(null)).toContain('helpers')
    expect(visibleTabs({ helper: false, rights: [] })).toContain('tier')
    expect(visibleTabs({ helper: true, rights: ['stats', 'uploads'] })).toEqual(['stats', 'symbol', 'cape'])
    expect(visibleTabs({ helper: true, rights: ['codes', 'packs'] })).toEqual(['codes', 'packs'])
    expect(visibleTabs({ helper: true, rights: [] })).toEqual([])
    expect(canInviteHelper([{}], 'starter')).toBe(false)
    expect(canInviteHelper([{}, {}], 'partner')).toBe(true)
    const friends = [{ uuid: A, name: 'Pixel' }, { uuid: B, name: 'Cutter' }, { uuid: 'd'.repeat(32), name: 'Ich' }]
    expect(invitableFriends(friends, [{ uuid: A }], 'd'.repeat(32)).map((f) => f.name)).toEqual(['Cutter'])
  })
})

describe('Fan-Symbol aus einem Bild', () => {
  function solid(w: number, h: number, alpha = 255) {
    const img = rgba(w, h)
    for (let i = 0; i < img.data.length; i += 4) {
      img.data[i] = 200
      img.data[i + 3] = alpha
    }
    return img
  }

  it('wählt eine passende Größe', () => {
    expect(symbolSize(16, 16)).toBe(16)
    expect(symbolSize(10, 10)).toBe(16)
    expect(symbolSize(48, 100)).toBe(48)
    expect(symbolSize(128, 128)).toBe(64)
    expect(symbolSize(160, 160)).toBe(40)
    expect(symbolSize(127, 127)).toBe(64)
    expect(centerSquare(100, 60)).toEqual({ x: 20, y: 0, w: 60, h: 60 })
  })

  it('baut einen senkrechten Streifen quadratischer Bilder', () => {
    const frames = Array.from({ length: 12 }, () => solid(64, 32))
    const strip = buildSymbolStrip(frames, 8, 32)!
    expect(strip.frames).toBe(8)
    expect(strip.sourceFrames).toBe(12)
    expect([strip.strip.width, strip.strip.height]).toEqual([32, 32 * 8])
    expect(symbolVisible(strip.strip)).toBe(true)
  })

  it('Starter: nur ein Bild, Größe wird begrenzt', () => {
    const s = buildSymbolStrip([solid(20, 20), solid(20, 20)], 1)!
    expect([s.frames, s.size, s.strip.height]).toEqual([1, 20, 20])
    expect(buildSymbolStrip([solid(8, 8)], 8, 200)!.size).toBe(64)
    expect(buildSymbolStrip([], 8)).toBeNull()
    expect(symbolVisible(buildSymbolStrip([solid(16, 16, 0)], 1)!.strip)).toBe(false)
  })

  it('verteilt die Dauer einer Animation auf die behaltenen Bilder', () => {
    expect(symbolFrameMs(1600, 8)).toBe(200)
    expect(symbolFrameMs(100, 8)).toBe(50)
    expect(symbolFrameMs(60_000, 2)).toBe(1000)
    expect(symbolFrameMs(0, 3)).toBe(150)
  })
})

describe('Schemas', () => {
  it('lesen die Antworten des Kerns, auch ohne Helfer-Felder', () => {
    const me = myCreatorSchema.parse({
      support: { creator: null, startedAt: null, lastCreatorUuid: null, switchAvailableAt: null },
      fanCapes: [],
      blocked: [],
      showFanSymbols: true,
      creator: null,
      application: null,
    })
    expect(me.helping).toEqual([])
    expect(me.helperInvites).toEqual([])
    const self = creatorSelfSchema.parse({
      uuid: A, name: 'Pixelmaus', tier: 'partner', state: 'active', reason: null, since: null, channels: [],
      codes: [{ code: 'TRS-PIXEL', kind: 'main', uses: 3, maxUses: null, expiresAt: null, active: true, createdAt: null }],
      limits: { extraCodes: 5, maxUsesPerCode: 1000, symbolFrames: 8, fanCape: 'standard' },
      stats: { activeFans: 1, redemptions: 1, fanCapeHolders: 0, newPlayers: 0, packInstalls: 0, daily: [] },
      milestone: null, nextMilestone: { name: 'bronze', at: 100 },
      symbol: { current: null, pending: null, rejected: null }, fanCape: { current: null, pending: null, rejected: null },
      requests: [], packs: [],
    })
    expect(self.helpers).toEqual([])
    expect(self.viewer).toBeNull()
  })

  it('kennen die Echtzeit-Ereignisse', () => {
    const [updated, milestone, invite, removed] = creatorEventSchemas
    expect(updated.safeParse({ type: 'creator_updated', kind: 'helper_joined', reason: null, tier: null, milestone: null, helper: { uuid: B, name: 'Cutter' } }).success).toBe(true)
    expect(updated.safeParse({ type: 'creator_updated', kind: 'nuke', reason: null, tier: null, milestone: null, helper: null }).success).toBe(false)
    expect(milestone.safeParse({ type: 'creator_milestone', creator: { uuid: A, name: 'Pixelmaus' }, milestone: 'gold' }).success).toBe(true)
    expect(invite.safeParse({ type: 'creator_helper_invite', creator: { uuid: A, name: 'Pixelmaus' }, rights: ['stats'] }).success).toBe(true)
    expect(removed.safeParse({ type: 'creator_helper_removed', creator: null }).success).toBe(true)
  })
})
