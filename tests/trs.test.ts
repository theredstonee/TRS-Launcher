import { beforeEach, describe, expect, it } from 'vitest'
import { setLocale } from '../app/utils/i18n'
import {
  trsCapeNameSchema,
  trsCapeSchema,
  trsDate,
  trsFrameIndex,
  trsFriendsSchema,
  trsJoinInstance,
  trsNewCodesSchema,
  trsNormalizeCode,
  trsParse,
  trsPresenceText,
  trsRedeemCodeSchema,
  trsSortFriends,
  trsStatusLabel,
  trsStatusSchema,
  trsTargetSchema,
  trsUnlockLabel,
} from '../app/utils/trs'

// Die meisten Erwartungen sind die deutschen Texte; Englisch wird extra geprüft.
beforeEach(async () => {
  await setLocale('de')
})

const cape = {
  id: 'team',
  name: 'TRS Team',
  kind: 'builtin',
  unlock: 'admin',
  status: 'approved',
  width: 128,
  height: 64,
  scale: 2,
  frames: 8,
  frameTimeMs: 150,
  owned: true,
  active: false,
  rejectReason: null,
  texture: 'data:image/png;base64,iVBORw0KGgo=',
}

describe('TRS-Daten aus dem Kern', () => {
  it('prüft Status und Umhänge', () => {
    expect(trsStatusSchema.safeParse({ consent: null, account: '75c1a6f3112240abbdb57b9d21c64232', signedIn: false }).success).toBe(true)
    expect(trsStatusSchema.safeParse({ consent: 'vielleicht', account: null, signedIn: false }).success).toBe(false)
    expect(trsCapeSchema.safeParse(cape).success).toBe(true)
    // Texturen nur als PNG-Data-URL – nie eine fremde Adresse.
    expect(trsCapeSchema.safeParse({ ...cape, texture: 'https://evil.example/x.png' }).success).toBe(false)
    expect(trsCapeSchema.safeParse({ ...cape, id: '../x' }).success).toBe(false)
    expect(() => trsParse(trsCapeSchema, { ...cape, frames: 0 })).toThrow('ungültig')
  })

  it('prüft die Freundesliste', () => {
    const view = {
      friends: [
        {
          uuid: 'b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0',
          name: 'Bob',
          since: null,
          presence: { state: 'in-game', game: { version: '1.21.1', loader: 'fabric', server: 'play.example.net' }, updatedAt: null },
        },
      ],
      requests: { incoming: [], outgoing: [] },
    }
    expect(trsFriendsSchema.safeParse(view).success).toBe(true)
    const broken = structuredClone(view)
    broken.friends[0]!.presence!.state = 'away'
    expect(trsFriendsSchema.safeParse(broken).success).toBe(false)
  })
})

describe('Animierte Umhänge', () => {
  it('wählt den Frame nach der Wanduhr', () => {
    expect(trsFrameIndex(0, 8, 150)).toBe(0)
    expect(trsFrameIndex(149, 8, 150)).toBe(0)
    expect(trsFrameIndex(150, 8, 150)).toBe(1)
    expect(trsFrameIndex(150 * 8, 8, 150)).toBe(0)
    expect(trsFrameIndex(1_758_650_000_123, 8, 150)).toBe(Math.floor(1_758_650_000_123 / 150) % 8)
    expect(trsFrameIndex(12345, 1, null)).toBe(0)
  })

  it('beschriftet den Sperr-Status', () => {
    expect(trsUnlockLabel({ kind: 'builtin', unlock: 'free' })).toBe('Frei')
    expect(trsUnlockLabel({ kind: 'builtin', unlock: 'code' })).toBe('Code')
    expect(trsUnlockLabel({ kind: 'builtin', unlock: 'admin' })).toBe('Team')
    expect(trsUnlockLabel({ kind: 'upload', unlock: 'owner' })).toBe('Eigener')
    expect(trsStatusLabel('pending')).toBe('Wartet auf Freigabe')
    expect(trsStatusLabel('approved')).toBeNull()
  })

  it('beschriftet auf Englisch', async () => {
    await setLocale('en')
    expect(trsUnlockLabel({ kind: 'builtin', unlock: 'free' })).toBe('Free')
    expect(trsUnlockLabel({ kind: 'builtin', unlock: 'other' })).toBe('Locked')
    expect(trsStatusLabel('rejected')).toBe('Rejected')
  })
})

describe('Eingaben', () => {
  it('normalisiert Codes wie der Server', () => {
    expect(trsNormalizeCode('7k3qf-m2xpa-9rtvb-c4hjn')).toBe('7K3QFM2XPA9RTVBC4HJN')
    expect(trsNormalizeCode('OOOOO IIIII LLLLL 22222')).toBe('00000111111111122222')
    expect(trsNormalizeCode('UUUUU-UUUUU-UUUUU-UUUUU')).toBeNull()
    expect(trsRedeemCodeSchema.safeParse('kurz').success).toBe(false)
    expect(trsRedeemCodeSchema.safeParse('7K3QF-M2XPA-9RTVB-C4HJN').success).toBe(true)
  })

  it('prüft Namen und Ziele', () => {
    expect(trsTargetSchema.safeParse('Theredstonee').success).toBe(true)
    expect(trsTargetSchema.safeParse('75c1a6f3-1122-40ab-bdb5-7b9d21c64232').success).toBe(true)
    expect(trsTargetSchema.safeParse('zwei Wörter').success).toBe(false)
    expect(trsTargetSchema.safeParse('x'.repeat(17)).success).toBe(false)
    expect(trsCapeNameSchema.safeParse('Mein Umhang!').success).toBe(true)
    expect(trsCapeNameSchema.safeParse('<script>').success).toBe(false)
  })

  it('prüft neue Codes', () => {
    const base = { capeId: 'team', maxUses: 1, count: 1, expiresAt: null, note: null }
    expect(trsNewCodesSchema.safeParse(base).success).toBe(true)
    expect(trsNewCodesSchema.safeParse({ ...base, count: 101 }).success).toBe(false)
    expect(trsNewCodesSchema.safeParse({ ...base, maxUses: 0 }).success).toBe(false)
    expect(trsNewCodesSchema.safeParse({ ...base, expiresAt: '2001-01-01T00:00:00Z' }).success).toBe(false)
    expect(trsNewCodesSchema.safeParse({ ...base, expiresAt: '2999-01-01T00:00:00Z' }).success).toBe(true)
  })

  it('meldet Fehler in der eingestellten Sprache', async () => {
    const tooMany = { capeId: 'team', maxUses: 1, count: 101, expiresAt: null, note: null }
    expect(trsNewCodesSchema.safeParse(tooMany).error?.issues[0]?.message).toBe('Höchstens 100 Codes auf einmal.')
    expect(trsRedeemCodeSchema.safeParse('kurz').error?.issues[0]?.message).toContain('20 Zeichen')
    await setLocale('en')
    expect(trsNewCodesSchema.safeParse(tooMany).error?.issues[0]?.message).toBe('At most 100 codes at once.')
    expect(trsTargetSchema.safeParse('zwei Wörter').error?.issues[0]?.message).toContain('Minecraft name')
  })
})

describe('Freunde', () => {
  const game = { version: '1.21.1', loader: 'fabric' as const, server: 'play.example.net' }

  it('beschreibt den Status', () => {
    expect(trsPresenceText(null)).toBe('Offline')
    expect(trsPresenceText({ state: 'online', game: null, updatedAt: null })).toBe('Online im Launcher')
    expect(trsPresenceText({ state: 'in-game', game, updatedAt: null })).toBe('Spielt 1.21.1 (Fabric) auf play.example.net')
    expect(trsPresenceText({ state: 'in-game', game: { version: '1.8.9', loader: 'vanilla' }, updatedAt: null })).toBe('Spielt 1.8.9')
    expect(trsPresenceText({ state: 'in-game', game: { version: '1.8.9', loader: 'vanilla', server: 'mc.example.net' }, updatedAt: null })).toBe(
      'Spielt 1.8.9 auf mc.example.net',
    )
  })

  it('beschreibt den Status auf Englisch', async () => {
    await setLocale('en')
    expect(trsPresenceText(null)).toBe('Offline')
    expect(trsPresenceText({ state: 'online', game: null, updatedAt: null })).toBe('Online in the launcher')
    expect(trsPresenceText({ state: 'in-game', game, updatedAt: null })).toBe('Playing 1.21.1 (Fabric) on play.example.net')
    expect(trsPresenceText({ state: 'in-game', game: null, updatedAt: null })).toBe('In game')
  })

  it('formatiert Daten nach Sprache', async () => {
    expect(trsDate(null)).toBe('–')
    expect(trsDate('kaputt')).toBe('–')
    expect(trsDate('2026-09-23T12:00:00Z')).toBe('23.09.2026')
    await setLocale('en')
    expect(trsDate('2026-09-23T12:00:00Z')).toBe('09/23/2026')
  })

  it('sortiert: im Spiel, online, offline', () => {
    const list = trsSortFriends([
      { name: 'zed', presence: null },
      { name: 'Anna', presence: null },
      { name: 'Bob', presence: { state: 'online' as const, game: null, updatedAt: null } },
      { name: 'Cid', presence: { state: 'in-game' as const, game, updatedAt: null } },
    ])
    expect(list.map((f) => f.name)).toEqual(['Cid', 'Bob', 'Anna', 'zed'])
  })

  it('findet die passende Instanz zum Beitreten', () => {
    const instances = [
      { id: 'zuletzt', gameVersion: '1.20.1', loader: { kind: 'vanilla' } },
      { id: 'gleich-vanilla', gameVersion: '1.21.1', loader: { kind: 'vanilla' } },
      { id: 'genau', gameVersion: '1.21.1', loader: { kind: 'fabric' } },
    ]
    expect(trsJoinInstance(instances, game)?.id).toBe('genau')
    expect(trsJoinInstance(instances, { ...game, loader: 'forge' })?.id).toBe('gleich-vanilla')
    expect(trsJoinInstance(instances, { ...game, version: '1.12.2' })?.id).toBe('zuletzt')
    expect(trsJoinInstance([], game)).toBeNull()
  })
})
