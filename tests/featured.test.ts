import { describe, expect, it } from 'vitest'
import {
  adminFeaturedPackSchema,
  featuredListSchema,
  featuredMove,
  featuredStatus,
  featuredStep,
  featuredText,
  featuredTitle,
  featuredVersion,
  isoToLocalInput,
  localInputToIso,
  looksLikePackLink,
  parseModpackLink,
  swipeDirection,
} from '../app/utils/featured'
import { teamSections } from '../app/utils/teamAccess'

// Empfohlene Modpacks: Schema (was der Kern liefert), Texte der Folie je Sprache, Blättern/Wischen,
// Zustand und Reihenfolge in der Team-Liste, Zeitraum-Felder und der Deep Link.

const base = {
  id: 'fp1',
  source: 'modrinth',
  projectId: '1KVo5zza',
  slug: 'fabulously-optimized',
  url: 'https://modrinth.com/modpack/fabulously-optimized',
  title: 'Fabulously Optimized',
  summary: 'Improve your graphics and performance',
  author: 'robotkoer',
  downloads: 12_345_678,
  loaders: ['fabric'],
  gameVersions: ['1.21.4', '1.21.1'],
  iconUrl: 'https://trs-launcher.theredstonee.de/v1/site/featured-packs/fp1/icon?v=1',
  bannerUrl: null,
  badge: 'recommended',
  texts: { en: { title: 'Our pick', text: 'Fast and pretty.' }, de: { title: null, text: 'Schnell und schön.' } },
  pinned: true,
  startsAt: null,
  endsAt: null,
  updatedAt: '2026-10-20T10:00:00.000Z',
}

describe('Schema', () => {
  it('nimmt gültige Einträge an und wirft kaputte einzeln raus', () => {
    const list = featuredListSchema.parse([
      base,
      { ...base, id: 'bad id' },
      { ...base, id: 'cf', source: 'curseforge', projectId: 'abc', url: 'https://www.curseforge.com/minecraft/modpacks/x' },
      { ...base, id: 'evil', url: 'https://evil.example/modpack/x' },
      { ...base, id: 'js', iconUrl: 'javascript:alert(1)', badge: 'hot' },
      { ...base, id: 'cf2', source: 'curseforge', projectId: '925200', url: 'https://www.curseforge.com/minecraft/modpacks/atm10' },
      'kaputt',
    ])
    expect(list.map((p) => p.id)).toEqual(['fp1', 'js', 'cf2'])
    // Unsichere Bild-URL und unbekanntes Abzeichen werden still zu null.
    expect(list[1]!.iconUrl).toBeNull()
    expect(list[1]!.badge).toBeNull()
    expect(() => featuredListSchema.parse(Array.from({ length: 21 }, () => base))).toThrow()
  })

  it('Team-Einträge haben Zustand und Ersteller', () => {
    const a = adminFeaturedPackSchema.parse({ ...base, active: false, createdBy: 'Theredstonee', createdAt: '2026-10-19T08:00:00Z', refreshedAt: null })
    expect(a.active).toBe(false)
    expect(a.createdBy).toBe('Theredstonee')
    expect(adminFeaturedPackSchema.parse(base).active).toBe(false)
  })
})

describe('Folie', () => {
  const pack = featuredListSchema.parse([base])[0]!

  it('Deutsch: eigener deutscher Text, sonst englischer, sonst Zusammenfassung', () => {
    expect(featuredTitle(pack, 'de')).toBe('Our pick')
    expect(featuredText(pack, 'de')).toBe('Schnell und schön.')
    const noDe = { ...pack, texts: { en: { title: null, text: 'Fast.' }, de: { title: null, text: null } } }
    expect(featuredText(noDe, 'de')).toBe('Fast.')
    expect(featuredTitle(noDe, 'de')).toBe('Fabulously Optimized')
    const none = { ...pack, texts: { en: { title: null, text: null }, de: { title: null, text: '  ' } } }
    expect(featuredText(none, 'de')).toBe('Improve your graphics and performance')
  })

  it('andere Sprachen: englischer Text, sonst Zusammenfassung (nie Deutsch)', () => {
    expect(featuredText(pack, 'es')).toBe('Fast and pretty.')
    expect(featuredTitle(pack, 'fr')).toBe('Our pick')
    const deOnly = { ...pack, texts: { en: { title: null, text: null }, de: { title: 'Unser Tipp', text: 'Nur Deutsch' } } }
    expect(featuredText(deOnly, 'en')).toBe('Improve your graphics and performance')
    expect(featuredTitle(deOnly, 'en')).toBe('Fabulously Optimized')
    expect(featuredVersion(pack)).toBe('1.21.4')
  })

  it('blättert rundum und erkennt Wischgesten', () => {
    expect(featuredStep(0, -1, 3)).toBe(2)
    expect(featuredStep(2, 1, 3)).toBe(0)
    expect(featuredStep(0, 1, 0)).toBe(0)
    expect(swipeDirection(-80, 10)).toBe(1)
    expect(swipeDirection(80, 10)).toBe(-1)
    expect(swipeDirection(20, 0)).toBe(0)
    expect(swipeDirection(60, 70)).toBe(0)
  })
})

describe('Team-Liste', () => {
  const now = Date.parse('2026-10-20T12:00:00Z')

  it('Zustand nach Zeitraum', () => {
    expect(featuredStatus({ startsAt: null, endsAt: null }, now)).toBe('active')
    expect(featuredStatus({ startsAt: '2026-10-21T00:00:00Z', endsAt: null }, now)).toBe('scheduled')
    expect(featuredStatus({ startsAt: null, endsAt: '2026-10-20T11:59:00Z' }, now)).toBe('expired')
    expect(featuredStatus({ startsAt: '2026-10-01T00:00:00Z', endsAt: '2026-11-01T00:00:00Z' }, now)).toBe('active')
  })

  it('verschiebt Einträge', () => {
    expect(featuredMove(['a', 'b', 'c'], 0, 2)).toEqual(['b', 'c', 'a'])
    expect(featuredMove(['a', 'b', 'c'], 2, 0)).toEqual(['c', 'a', 'b'])
    expect(featuredMove(['a', 'b'], 0, 5)).toEqual(['a', 'b'])
  })

  it('Zeitraum-Felder (Ortszeit) ↔ ISO', () => {
    const iso = '2026-10-20T08:30:00.000Z'
    expect(localInputToIso(isoToLocalInput(iso))).toBe(iso)
    expect(isoToLocalInput(null)).toBe('')
    expect(isoToLocalInput('kaputt')).toBe('')
    expect(localInputToIso('')).toBeNull()
    expect(localInputToIso('nope')).toBeNull()
  })

  it('erkennt Modrinth-/CurseForge-Links', () => {
    expect(looksLikePackLink('https://modrinth.com/modpack/fo')).toBe(true)
    expect(looksLikePackLink('modrinth.com/modpack/fo')).toBe(true)
    expect(looksLikePackLink('https://www.curseforge.com/minecraft/modpacks/atm10')).toBe(true)
    expect(looksLikePackLink('https://evil.example/modrinth.com')).toBe(false)
    expect(looksLikePackLink('javascript:alert(1)')).toBe(false)
    expect(looksLikePackLink('')).toBe(false)
  })

  it('hat einen eigenen Bereich mit eigenem Recht', () => {
    expect(teamSections.find((s) => s.to === '/admin/featured')?.perms).toEqual(['featured.manage'])
  })
})

describe('Deep Link', () => {
  it('nimmt nur gültige Projekte an', () => {
    expect(parseModpackLink('modrinth/AANobbMI')).toEqual({ platform: 'modrinth', projectId: 'AANobbMI' })
    expect(parseModpackLink('curseforge/925200')).toEqual({ platform: 'curseforge', projectId: '925200' })
    expect(parseModpackLink('curseforge/abc')).toBeNull()
    expect(parseModpackLink('modrinth/../x')).toBeNull()
    expect(parseModpackLink('github/x')).toBeNull()
    expect(parseModpackLink(42)).toBeNull()
  })
})
