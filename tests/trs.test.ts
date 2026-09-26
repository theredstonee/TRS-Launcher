import { beforeEach, describe, expect, it } from 'vitest'
import { setLocale } from '../app/utils/i18n'
import {
  trsAdminCapeSchema,
  trsCapeHoldersSchema,
  trsCapeNameSchema,
  trsCapeOffersSchema,
  trsCapeSchema,
  trsCapeSourceSchema,
  trsDate,
  trsFrameIndex,
  trsFriendsSchema,
  trsJoinInstance,
  trsNewCodesSchema,
  trsNormalizeCode,
  trsNormalizeWebLoginCode,
  trsParse,
  trsPresenceText,
  trsRedeemCodeSchema,
  trsSortFriends,
  trsStatusLabel,
  trsStatusSchema,
  trsSyncEventSchema,
  trsSyncLabel,
  trsSyncStatusSchema,
  trsTargetSchema,
  trsUnlockLabel,
  trsWebLoginCodeSchema,
  TRS_HOST,
  trsRedeemSchema,
  trsHatSchema,
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
  shareable: false,
  shared: null,
  holders: 0,
}

const ALEX = { uuid: '75c1a6f3112240abbdb57b9d21c64232', name: 'Alex' }
const BOB = { uuid: 'b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0', name: 'Bob' }

describe('TRS-Daten aus dem Kern', () => {
  it('prüft Status und Umhänge', () => {
    expect(trsStatusSchema.safeParse({ consent: null, account: '75c1a6f3112240abbdb57b9d21c64232', signedIn: false }).success).toBe(true)
    expect(trsStatusSchema.safeParse({ consent: 'vielleicht', account: null, signedIn: false }).success).toBe(false)
    expect(trsCapeSchema.safeParse(cape).success).toBe(true)
    // Texturen nur als PNG-Data-URL – nie eine fremde Adresse.
    expect(trsCapeSchema.safeParse({ ...cape, texture: 'https://evil.example/x.png' }).success).toBe(false)
    expect(trsCapeSchema.safeParse({ ...cape, id: '../x' }).success).toBe(false)
    expect(() => trsParse(trsCapeSchema, { ...cape, frames: 0 })).toThrow('ungültig')
    // HD-Umhänge bis Faktor 8 (512×256) – darüber nicht.
    expect(trsCapeSchema.safeParse({ ...cape, width: 512, height: 256, scale: 8 }).success).toBe(true)
    expect(trsCapeSchema.safeParse({ ...cape, width: 576, height: 288, scale: 9 }).success).toBe(false)
  })

  it('prüft Umhänge in der Prüfliste (Größe, Hochlader)', () => {
    const admin = {
      ...cape,
      kind: 'upload',
      unlock: 'owner',
      status: 'pending',
      owner: { uuid: '75c1a6f3112240abbdb57b9d21c64232', name: 'Theredstonee' },
      createdAt: '2026-09-25T10:00:00Z',
      reviewedAt: null,
      reviewedBy: null,
      reports: { count: 0, reasons: {} },
      bytes: 123_456,
      ownerStats: { uploads: 3, approved: 1, pending: 1, rejected: 1 },
    }
    expect(trsAdminCapeSchema.safeParse(admin).success).toBe(true)
    // Ältere Server kennen Größe und Statistik noch nicht.
    expect(trsAdminCapeSchema.safeParse({ ...admin, bytes: null, ownerStats: null }).success).toBe(true)
    expect(trsAdminCapeSchema.safeParse({ ...admin, bytes: -1 }).success).toBe(false)
  })

  it('prüft gewählte Dateien für den Umhang-Dialog', () => {
    const png = 'data:image/png;base64,iVBORw0KGgo='
    expect(trsCapeSourceSchema.safeParse({ kind: 'image', name: 'a', dataUrl: png, width: 64, height: 32 }).success).toBe(true)
    expect(trsCapeSourceSchema.safeParse({ kind: 'image', name: 'a', dataUrl: 'data:image/jpeg;base64,/9j/', width: 1, height: 1 }).success).toBe(true)
    // Nur Bild-Data-URLs – kein SVG, keine fremden Adressen.
    expect(trsCapeSourceSchema.safeParse({ kind: 'image', name: 'a', dataUrl: 'data:image/svg+xml;base64,PHN2Zz4=', width: 1, height: 1 }).success).toBe(false)
    expect(trsCapeSourceSchema.safeParse({ kind: 'image', name: 'a', dataUrl: 'https://evil.example/x.png', width: 1, height: 1 }).success).toBe(false)
    const gif = { kind: 'gif', name: 'g', width: 20, height: 32, frames: [png, png], durationMs: 200, sourceFrames: 40 }
    expect(trsCapeSourceSchema.safeParse(gif).success).toBe(true)
    expect(trsCapeSourceSchema.safeParse({ ...gif, frames: Array(17).fill(png) }).success).toBe(false)
    expect(trsCapeSourceSchema.safeParse({ kind: 'studio', name: 'ender', frames: 4, scale: 8 }).success).toBe(true)
    expect(trsCapeSourceSchema.safeParse({ kind: 'studio', name: 'x', frames: 4, scale: 99 }).success).toBe(false)
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
      capeOffers: 2,
    }
    expect(trsFriendsSchema.safeParse(view).success).toBe(true)
    expect(trsFriendsSchema.safeParse({ ...view, capeOffers: -1 }).success).toBe(false)
    const broken = structuredClone(view)
    broken.friends[0]!.presence!.state = 'away'
    expect(trsFriendsSchema.safeParse(broken).success).toBe(false)
  })
})

describe('Umhänge teilen', () => {
  const upload = { ...cape, id: 'u0123456789abcdef0123', kind: 'upload', unlock: 'owner', frames: 1, frameTimeMs: null }

  it('prüft geteilte Umhänge, Angebote und Inhaber', () => {
    const shared = { ...upload, shareable: true, shared: { from: BOB, creator: ALEX }, holders: 1 }
    expect(trsCapeSchema.safeParse(shared).success).toBe(true)
    expect(trsCapeSchema.safeParse({ ...shared, shared: { from: { uuid: 'x', name: 'Bob' }, creator: ALEX } }).success).toBe(false)
    expect(trsCapeSchema.safeParse({ ...shared, holders: -1 }).success).toBe(false)

    const offers = {
      incoming: [{ cape: shared, from: BOB, creator: ALEX, createdAt: '2026-09-25T18:00:00.000Z' }],
      outgoing: [{ cape: upload, to: BOB, createdAt: null }],
    }
    expect(trsCapeOffersSchema.safeParse(offers).success).toBe(true)
    expect(trsCapeOffersSchema.safeParse({ ...offers, incoming: [{ ...offers.incoming[0], from: null }] }).success).toBe(false)

    const holders = {
      holders: [{ ...BOB, status: 'accepted', grantedBy: ALEX, createdAt: 'x', acceptedAt: 'y' }],
      count: 1,
      limit: 20,
    }
    expect(trsCapeHoldersSchema.safeParse(holders).success).toBe(true)
    expect(trsCapeHoldersSchema.safeParse({ ...holders, holders: [{ ...holders.holders[0], status: 'pending' }] }).success).toBe(false)
  })

  it('beschriftet geteilte Umhänge', async () => {
    const shared = { kind: 'upload' as const, unlock: 'owner' as const, shared: { from: BOB, creator: ALEX } }
    expect(trsUnlockLabel(shared)).toBe('Geteilt')
    expect(trsUnlockLabel({ kind: 'upload', unlock: 'owner', shared: null })).toBe('Eigener')
    await setLocale('en')
    expect(trsUnlockLabel(shared)).toBe('Shared')
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

  it('normalisiert Codes der Website-Anmeldung wie der Kern', async () => {
    expect(trsNormalizeWebLoginCode('ABCD-1234')).toBe('ABCD-1234')
    expect(trsNormalizeWebLoginCode(' abcd1234 ')).toBe('ABCD-1234')
    expect(trsNormalizeWebLoginCode('ab cd 12 34')).toBe('ABCD-1234')
    for (const bad of ['', 'ABCD', 'ABC-12345', 'ABCD--1234', 'ABCD-123', 'ÄBCD-1234', 'ABCD_1234', 'A'.repeat(40)]) {
      expect(trsNormalizeWebLoginCode(bad), bad).toBeNull()
    }
    expect(trsWebLoginCodeSchema.parse('abcd-1234')).toBe('ABCD-1234')
    expect(trsWebLoginCodeSchema.safeParse('kurz').error?.issues[0]?.message).toContain('ABCD-1234')
    await setLocale('en')
    expect(trsWebLoginCodeSchema.safeParse('kurz').error?.issues[0]?.message).toContain('letters and digits')
  })

  it('kennt die neue Adresse der TRS API', () => {
    expect(TRS_HOST).toBe('trs-launcher.theredstonee.de')
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

describe('TRS-Synchronisation', () => {
  const status = { active: true, syncing: false, lastSyncAt: null, problem: null }

  it('prüft Status und Event aus dem Kern', () => {
    expect(trsSyncStatusSchema.safeParse(status).success).toBe(true)
    expect(trsSyncStatusSchema.safeParse({ ...status, problem: 'kaputt' }).success).toBe(false)
    const event = {
      account: '75c1a6f3112240abbdb57b9d21c64232',
      changes: { skins: true, presets: false, settings: true },
      status: { ...status, lastSyncAt: '2026-09-25T10:00:00Z' },
    }
    expect(trsSyncEventSchema.safeParse(event).success).toBe(true)
    expect(trsSyncEventSchema.safeParse({ ...event, account: '../x' }).success).toBe(false)
    expect(trsSyncEventSchema.safeParse({ ...event, changes: { skins: 'ja' } }).success).toBe(false)
  })

  it('zeigt einen dezenten Hinweis – nur wenn aktiv', () => {
    expect(trsSyncLabel(null)).toBeNull()
    expect(trsSyncLabel({ ...status, active: false, lastSyncAt: '2026-09-25T10:00:00Z' })).toBeNull()
    expect(trsSyncLabel({ ...status, syncing: true })).toBe('Synchronisiere mit deinem TRS-Konto …')
    expect(trsSyncLabel({ ...status, problem: 'offline' })).toContain('nicht erreichbar')
    expect(trsSyncLabel(status)).toBe('Wird gleich mit deinem TRS-Konto synchronisiert')
    const twoMinutesAgo = new Date(Date.now() - 2 * 60_000).toISOString()
    expect(trsSyncLabel({ ...status, lastSyncAt: twoMinutesAgo })).toBe('Mit TRS-Konto synchronisiert · vor 2 Minuten')
  })
})

describe('Kosmetik-Codes und Kopf-Kosmetik', () => {
  it('liest Umhang- und Kosmetik-Ergebnisse beim Einlösen', () => {
    expect(trsRedeemSchema.parse({ kind: 'cape', capeId: 'team', cosmeticId: null, name: 'TRS Team', alreadyOwned: false, wearableHat: false }).kind).toBe('cape')
    const duck = trsRedeemSchema.parse({ kind: 'cosmetic', capeId: null, cosmeticId: 'rubber_duck', name: 'Quietscheente', alreadyOwned: false, wearableHat: true })
    expect(duck.wearableHat).toBe(true)
    expect(() => trsRedeemSchema.parse({ kind: 'other', capeId: null, cosmeticId: null, name: 'x', alreadyOwned: false, wearableHat: false })).toThrow()
  })

  it('prüft Kopf-Kosmetik streng', () => {
    expect(trsHatSchema.parse({ id: 'rubber_duck', name: 'Quietscheente', template: 'duck', equipped: true }).equipped).toBe(true)
    expect(() => trsHatSchema.parse({ id: '../x', name: 'x', template: 'duck', equipped: false })).toThrow()
  })
})
