import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { i18n, setLocale } from '../app/utils/i18n'
import { liveEventSchema } from '../app/utils/chat'
import {
  cleanRedeemInput,
  myPartnerSchema,
  normalizeRedeemCode,
  partnerCardSchema,
  partnerDashboardUrl,
  partnerRedeemText,
  partnerSwitchAt,
  partnerText,
  redeemOutcomeSchema,
  type PartnerRedeem,
} from '../app/utils/partner'

const card = {
  slug: 'craftinghosting',
  name: 'CraftingHosting',
  category: 'hosting',
  logo: null,
  text: { de: 'Kostenlose Server', en: 'Free servers' },
  links: [],
  code: 'CRAFTING',
  server: null,
  wikiUrl: null,
  supporters: 12,
  cosmetics: [],
}

describe('Partner: Code-Eingabe', () => {
  it('nimmt Creator-, Partner- und Umhang-Codes', () => {
    expect(normalizeRedeemCode(' crafting ')).toBe('CRAFTING')
    expect(normalizeRedeemCode('ch-7k2m-9qxa')).toBe('CH-7K2M-9QXA')
    expect(normalizeRedeemCode('7K3QF M2XPA 9RTVB C4HJN')).toBe('7K3QFM2XPA9RTVBC4HJN')
    expect(normalizeRedeemCode('7K3QF-M2XPA-9RTVB-C4HJN')).toBe('7K3QF-M2XPA-9RTVB-C4HJN')
    for (const bad of ['abc', '', 'CODE!', 'A'.repeat(33)]) expect(normalizeRedeemCode(bad), bad).toBeNull()
  })

  it('säubert beim Tippen', () => {
    expect(cleanRedeemInput('ch-7k2m 9qxa!')).toBe('CH-7K2M9QXA')
    expect(cleanRedeemInput('x'.repeat(40))).toHaveLength(32)
  })
})

describe('Partner: Schemas', () => {
  it('liest kaputte optionale Felder als leer', () => {
    const parsed = partnerCardSchema.parse({ ...card, category: 'weird', server: 'x', wikiUrl: 'http://x', links: 'nope', supporters: -1, logo: 'data:text/html,x' })
    expect(parsed.category).toBe('other')
    expect(parsed.server).toBeNull()
    expect(parsed.wikiUrl).toBeNull()
    expect(parsed.links).toEqual([])
    expect(parsed.supporters).toBe(0)
    expect(parsed.logo).toBeNull()
  })

  it('eigener Partner und Ergebnis des Einlösens', () => {
    const me = myPartnerSchema.parse({ support: { partner: null, startedAt: null, switchAvailableAt: null }, owned: [], member: [] })
    expect(me.support.partner).toBeNull()
    const r = redeemOutcomeSchema.parse({
      kind: 'partner',
      result: { partner: card, supportChanged: true, switchAvailableAt: null, granted: { capes: [{ capeId: 'partner_ch_cube', name: 'Würfel' }], cosmetics: [] }, oneTime: false },
    })
    expect(r.kind).toBe('partner')
    const cape = redeemOutcomeSchema.parse({
      kind: 'cape',
      result: { kind: 'cape', capeId: 'trs_gold', cosmeticId: null, name: 'Gold', alreadyOwned: false, wearableHat: false, slot: null },
    })
    expect(cape.kind).toBe('cape')
    expect(() => redeemOutcomeSchema.parse({ kind: 'badge', result: {} })).toThrow()
  })

  it('Echtzeit-Ereignisse gehören zu liveEventSchema', () => {
    expect(liveEventSchema.parse({ type: 'partner_support_changed' }).type).toBe('partner_support_changed')
    expect(liveEventSchema.parse({ type: 'partner_updated', slug: 'craftinghosting', kind: 'quota' }).type).toBe('partner_updated')
  })
})

describe('Partner: Anzeige', () => {
  let before: string
  beforeAll(async () => {
    before = i18n.global.locale.value
    await setLocale('de')
  })
  afterAll(async () => {
    await setLocale(before)
  })

  it('Text in der Sprache, sonst der andere', () => {
    expect(partnerText(card, 'de')).toBe('Kostenlose Server')
    expect(partnerText(card, 'fr')).toBe('Free servers')
    expect(partnerText({ text: { de: '', en: 'Only English' } }, 'de')).toBe('Only English')
  })

  it('Wechselsperre und Dashboard-Link', () => {
    const now = Date.parse('2026-11-21T12:00:00Z')
    expect(partnerSwitchAt('2026-12-20T10:00:00.000Z', now)).toBe('2026-12-20T10:00:00.000Z')
    expect(partnerSwitchAt('2026-11-01T10:00:00.000Z', now)).toBeNull()
    expect(partnerSwitchAt(null, now)).toBeNull()
    expect(partnerDashboardUrl([{ slug: 'craftinghosting' }], 'craftinghosting')).toMatch(/\/partner$/)
    expect(partnerDashboardUrl([{ slug: 'a-b' }, { slug: 'craftinghosting' }], 'craftinghosting')).toMatch(/\/partner\/craftinghosting$/)
  })

  it('Meldung nach dem Einlösen', () => {
    const base: PartnerRedeem = {
      partner: { ...card, category: 'hosting' },
      supportChanged: true,
      switchAvailableAt: '2026-12-20T10:00:00.000Z',
      granted: { capes: [{ capeId: 'partner_ch_cube', name: 'Würfel' }], cosmetics: [{ id: 'ch_hat', name: 'Hut', slot: 'hat' }] },
      oneTime: false,
    }
    expect(partnerRedeemText(base, 'craftinghosting')).toBe('Du unterstützt jetzt CraftingHosting. Freigeschaltet: Würfel und Hut.')
    const none = { ...base, supportChanged: false, granted: { capes: [], cosmetics: [] } }
    expect(partnerRedeemText(none, 'craftinghosting')).toBe('Du unterstützt CraftingHosting schon.')
    expect(partnerRedeemText(none, 'other')).toBe('Code von CraftingHosting eingelöst. Einen anderen Partner kannst du ab 20.12.2026 unterstützen.')
  })
})
