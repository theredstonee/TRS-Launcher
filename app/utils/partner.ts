import { z } from 'zod'
// Relativ importiert, damit Tests die Datei ohne Nuxt laden können.
import { creatorRedeemSchema } from './creator'
import { trsRedeemSchema } from './trs'
import { TEAM_WEBSITE } from './teamAccess'
import { formatList, formatShortDate } from './format'
import { t } from './i18n'

// Partner-Programm (API §44): Schemas für das, was der Kern liefert, und kleine
// Regeln für die Oberfläche (Code-Eingabe, Wechselsperre, Dashboard-Link).
// Getestet in tests/partner.test.ts.

export const partnerCategories = ['hosting', 'network', 'other'] as const
export type PartnerCategory = (typeof partnerCategories)[number]

const slug = z.string().regex(/^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$/)
const itemId = z.string().regex(/^[a-z0-9][a-z0-9_-]{0,39}$/)
const text = (max: number) => z.string().max(max)
const time = text(40).nullish().catch(null)
const count = z.number().int().min(0)
const https = z.string().startsWith('https://').max(500)
const itemKind = z.enum(['cape', 'cosmetic'])

export const partnerRefSchema = z.object({ slug, name: text(48) })

export const partnerCardSchema = z.object({
  slug,
  name: text(48),
  category: z.enum(partnerCategories).catch('other'),
  logo: z.string().startsWith('data:image/png;base64,').nullish().catch(null),
  text: z.object({ de: text(1200), en: text(1200) }).catch({ de: '', en: '' }),
  links: z.array(z.object({ label: text(48), url: https })).max(6).catch([]),
  code: text(32).nullish().catch(null),
  server: z.object({ name: text(64), address: text(255) }).nullish().catch(null),
  wikiUrl: https.nullish().catch(null),
  supporters: count.catch(0),
  cosmetics: z.array(z.object({ kind: itemKind, id: itemId, name: text(48) })).max(50).catch([]),
})

export const myPartnerSchema = z.object({
  support: z.object({ partner: partnerCardSchema.nullish().catch(null), startedAt: time, switchAvailableAt: time }),
  owned: z.array(z.object({ kind: itemKind, id: itemId, name: text(48), partner: partnerRefSchema.nullish().catch(null) })).max(200).catch([]),
  member: z.array(z.object({ slug, name: text(48), rights: z.array(text(20)).max(9).catch([]), owner: z.boolean().catch(false) })).max(20).catch([]),
})

export const partnerRedeemSchema = z.object({
  partner: partnerCardSchema,
  supportChanged: z.boolean(),
  switchAvailableAt: time,
  granted: z
    .object({
      capes: z.array(z.object({ capeId: itemId, name: text(48) })).max(50).catch([]),
      cosmetics: z.array(z.object({ id: itemId, name: text(48), slot: text(20).nullish().catch(null) })).max(50).catch([]),
    })
    .catch({ capes: [], cosmetics: [] }),
  oneTime: z.boolean().catch(false),
})

/** Gemeinsames Code-Feld (§44.2): was der Code war und was er gebracht hat. */
export const redeemOutcomeSchema = z.discriminatedUnion('kind', [
  z.object({ kind: z.literal('creator'), result: creatorRedeemSchema }),
  z.object({ kind: z.literal('partner'), result: partnerRedeemSchema }),
  z.object({ kind: z.literal('cape'), result: trsRedeemSchema }),
])

/** Echtzeit-Ereignisse (§44.8) – Teil von `liveEventSchema`. */
export const partnerEventSchemas = [
  z.object({ type: z.literal('partner_support_changed') }),
  z.object({ type: z.literal('partner_updated'), slug, kind: text(40).nullish().catch(null) }),
] as const

export type PartnerCard = z.infer<typeof partnerCardSchema>
export type MyPartner = z.infer<typeof myPartnerSchema>
export type PartnerRedeem = z.infer<typeof partnerRedeemSchema>
export type RedeemOutcome = z.infer<typeof redeemOutcomeSchema>
export type PartnerEvent = z.infer<(typeof partnerEventSchemas)[number]>
export type PartnerMembership = MyPartner['member'][number]

// --- Code-Eingabe ------------------------------------------------------------------------

export const REDEEM_CODE_MAX = 32

/** Beim Tippen: groß, nur A–Z, 0–9 und `-` (Umhang-Codes dürfen Leerzeichen haben – die fallen weg). */
export function cleanRedeemInput(input: string): string {
  return input.toUpperCase().replace(/[^A-Z0-9-]/g, '').slice(0, REDEEM_CODE_MAX)
}

/** Creator-, Partner- oder Umhang-Code → normalisiert oder `null` (Art entscheidet der Server). */
export function normalizeRedeemCode(input: string): string | null {
  const s = input.replace(/\s+/g, '').toUpperCase()
  return /^[A-Z0-9-]{4,32}$/.test(s) ? s : null
}

// --- Anzeige -----------------------------------------------------------------------------

/** Text des Partners in der Sprache: Deutsch für `de`, sonst Englisch – fehlt einer, der andere. */
export function partnerText(card: Pick<PartnerCard, 'text'>, locale: string): string {
  const de = card.text.de.trim()
  const en = card.text.en.trim()
  return locale === 'de' ? de || en : en || de
}

/** Wechselsperre: ab wann ein anderer Partner geht (`null` = jetzt). */
export function partnerSwitchAt(switchAvailableAt: string | null | undefined, now = Date.now()): string | null {
  const ms = switchAvailableAt ? Date.parse(switchAvailableAt) : Number.NaN
  return Number.isFinite(ms) && ms > now ? switchAvailableAt! : null
}

/** Link zum Partner-Dashboard auf der Website: `/partner`, bei mehreren `/partner/<slug>`. */
export function partnerDashboardUrl(member: Pick<PartnerMembership, 'slug'>[], slugOf: string): string {
  return member.length > 1 ? `${TEAM_WEBSITE}/partner/${encodeURIComponent(slugOf)}` : `${TEAM_WEBSITE}/partner`
}

/** Namen aller freigeschalteten Teile eines Partner-Codes. */
export function grantedNames(r: Pick<PartnerRedeem, 'granted'>): string[] {
  return [...r.granted.capes.map((c) => c.name), ...r.granted.cosmetics.map((c) => c.name)]
}

/** Meldung nach dem Einlösen eines Partner-Codes; `current` = unterstützter Partner danach (neu geladen). */
export function partnerRedeemText(r: PartnerRedeem, current: string | null): string {
  const name = r.partner.name
  const items = grantedNames(r)
  const granted = items.length ? ` ${t('partner.redeemed.granted', { items: formatList(items) })}` : ''
  if (r.supportChanged) return t('partner.redeemed.now', { name }) + granted
  if (current === r.partner.slug) return t('partner.redeemed.already', { name }) + granted
  return t('partner.redeemed.locked', { name, date: formatShortDate(r.switchAvailableAt) }) + granted
}
