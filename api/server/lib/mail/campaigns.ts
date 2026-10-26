import { randomBytes } from 'node:crypto'
import { z } from 'zod'
import { audit } from '../audit'
import { createCodes } from '../codes'
import type { AppContext } from '../context'
import { all, one, run, tx } from '../db'
import { ApiError, badRequest, conflict, notFound } from '../errors'
import { getCape } from '../capes'
import { getCosmetic } from '../cosmetics'
import type { BlogPost } from '../site'
import {
  BLOCK_TYPES,
  CAMPAIGN_LOOKS,
  NEWSLETTER_LANGS,
  NEWSLETTER_LIMITS as LIM,
  pickLang,
  type Block,
  type CampaignContent,
  type CampaignListItem,
  type CampaignStatus,
  type CampaignView,
  type NewsletterLang,
  type UpdateItem,
} from '../../../shared/newsletter'
import { actorId, actorRef, assertActorCan, type NewsletterActor } from './keys'
import { safeUrl } from './markdown'
import { cancelQueued, enqueue, queuedCount, requireMail, startCampaign } from './outbox'
import { creatorInfo, itemImage, renderCampaign, UNSUB_PLACEHOLDER, type RenderedMail } from './render'
import { maskEmail } from './tokens'
import { isLook, resolveLook, type Look } from './theme'

/**
 * Newsletter-Kampagnen (§40.4): Entwürfe aus Bausteinen mit Texten je Sprache, Vorschau (HTML je Sprache/Look),
 * Testmail an die eigene Adresse, planen, senden, abbrechen. `rev` schützt vor dem Überschreiben fremder Änderungen.
 */

export const CAMPAIGN_ID = /^[A-Za-z0-9_-]{16}$/
export const MAX_CAMPAIGNS = 1000
const SCHEDULE_MAX_MS = 90 * 24 * 60 * 60 * 1000

// ---------------------------------------------------------------- Schemas

const loc = (max: number) => z.strictObject({
  de: z.string().max(max).optional(),
  en: z.string().max(max).optional(),
  es: z.string().max(max).optional(),
})
const url = z.string().trim().max(LIM.url).refine((s) => safeUrl(s, 'https://x.invalid') !== null && !/^mailto:/i.test(s), 'must be https://… or a path starting with /')
const imgUrl = z.string().trim().max(LIM.url).refine((s) => /^(https:\/\/|\/(?!\/))/.test(s) && safeUrl(s, 'https://x.invalid') !== null, 'must be https://… or a path starting with /')
const blockId = z.string().regex(/^[A-Za-z0-9_-]{1,24}$/)
const updateItem = z.strictObject({ title: z.string().trim().min(1).max(200), text: z.string().trim().max(600) })

export const blockSchema = z.discriminatedUnion('type', [
  z.strictObject({ id: blockId, type: z.literal('title'), size: z.enum(['h1', 'h2']), kicker: loc(80).optional(), text: loc(LIM.title) }),
  z.strictObject({ id: blockId, type: z.literal('text'), text: loc(LIM.text), muted: z.boolean().optional() }),
  z.strictObject({ id: blockId, type: z.literal('button'), label: loc(LIM.label), url }),
  z.strictObject({ id: blockId, type: z.literal('divider') }),
  z.strictObject({
    id: blockId,
    type: z.literal('update'),
    version: z.string().regex(/^\d{1,3}\.\d{1,3}\.\d{1,3}$/),
    title: loc(80).optional(),
    accent: z.string().regex(/^#[0-9a-fA-F]{6}$/).nullable().optional(),
    motif: imgUrl.nullable().optional(),
    items: z.strictObject({
      de: z.array(updateItem).max(LIM.updateItems).optional(),
      en: z.array(updateItem).max(LIM.updateItems).optional(),
      es: z.array(updateItem).max(LIM.updateItems).optional(),
    }).default({}),
    button: loc(LIM.label).optional(),
    url: url.optional(),
  }),
  z.strictObject({
    id: blockId,
    type: z.literal('images'),
    images: z.array(z.strictObject({ src: imgUrl, alt: loc(LIM.caption).optional(), caption: loc(LIM.caption).optional() })).min(1).max(2),
  }),
  z.strictObject({
    id: blockId,
    type: z.literal('cosmetic'),
    item: z.strictObject({ kind: z.enum(['cape', 'cosmetic']), id: z.string().regex(/^[a-z0-9][a-z0-9_-]{0,39}$/) }).nullable().optional(),
    image: imgUrl.nullable().optional(),
    kicker: loc(80).optional(),
    title: loc(LIM.title),
    text: loc(1000).optional(),
    code: z.string().trim().max(40).regex(/^[A-Za-z0-9-]*$/).nullable().optional(),
    codeId: z.number().int().positive().nullable().optional(),
    codeExpiresAt: z.iso.datetime({ offset: true }).nullable().optional(),
  }),
  z.strictObject({ id: blockId, type: z.literal('creator'), uuid: z.string().regex(/^[0-9a-f]{32}$/), kicker: loc(80).optional(), text: loc(1000), url: url.nullable().optional() }),
])

export const contentSchema = z.strictObject({
  subject: loc(LIM.subject).default({}),
  preheader: loc(LIM.preheader).default({}),
  blocks: z.array(blockSchema).max(LIM.blocks).default([]),
}).superRefine((c, ctx) => {
  const ids = new Set<string>()
  for (const b of c.blocks) {
    if (ids.has(b.id)) ctx.addIssue({ code: 'custom', message: `duplicate block id ${b.id}`, path: ['blocks'] })
    ids.add(b.id)
  }
})

const langsSchema = z.array(z.enum(NEWSLETTER_LANGS)).min(1).max(3).nullable()

export const createBody = z.strictObject({
  name: z.string().trim().min(1).max(LIM.name).optional(),
  look: z.enum(CAMPAIGN_LOOKS).optional(),
  content: contentSchema.optional(),
})
export const patchBody = z.strictObject({
  rev: z.number().int().min(1),
  name: z.string().trim().min(1).max(LIM.name).optional(),
  look: z.enum(CAMPAIGN_LOOKS).optional(),
  content: contentSchema.optional(),
})
export const previewBody = z.strictObject({
  content: contentSchema,
  look: z.enum(CAMPAIGN_LOOKS).default('auto'),
  lang: z.enum(NEWSLETTER_LANGS).default('de'),
})
export const savedPreviewBody = z.strictObject({
  look: z.enum(CAMPAIGN_LOOKS).optional(),
  lang: z.enum(NEWSLETTER_LANGS).default('de'),
}).default({ lang: 'de' })
export const testBody = z.strictObject({
  look: z.enum(CAMPAIGN_LOOKS).optional(),
  lang: z.enum(NEWSLETTER_LANGS).default('de'),
}).default({ lang: 'de' })
export const scheduleBody = z.strictObject({ at: z.iso.datetime({ offset: true }), langs: langsSchema.optional() })
export const sendBody = z.strictObject({ langs: langsSchema.optional(), expectedCount: z.number().int().min(0).optional() }).default({})
export const codeBody = z.strictObject({
  blockId,
  maxUses: z.number().int().min(1).max(100_000),
  expiresAt: z.iso.datetime({ offset: true }).optional(),
  rev: z.number().int().min(1),
})
export const listQuery = z.strictObject({ status: z.enum(['draft', 'scheduled', 'sending', 'sent', 'cancelled']).optional() })

// ---------------------------------------------------------------- Lesen

interface CampaignRow {
  id: string
  name: string
  look: string
  content: string
  status: CampaignStatus
  langs: string | null
  scheduled_at: number | null
  started_at: number | null
  sent_at: number | null
  sent_look: string | null
  total: number
  sent_count: number
  failed_count: number
  rev: number
  created_at: number
  created_by: string
  updated_at: number
  updated_by: string
}

const iso = (t: number | null) => (t === null ? null : new Date(t).toISOString())

function parseContent(raw: string): CampaignContent {
  try {
    const r = contentSchema.safeParse(JSON.parse(raw))
    if (r.success) return r.data as CampaignContent
  } catch {
    // kaputt → leer
  }
  return { subject: {}, preheader: {}, blocks: [] }
}

function lookOf(r: CampaignRow): (typeof CAMPAIGN_LOOKS)[number] {
  return (CAMPAIGN_LOOKS as readonly string[]).includes(r.look) ? r.look as (typeof CAMPAIGN_LOOKS)[number] : 'auto'
}

function viewOf(ctx: AppContext, r: CampaignRow): CampaignView {
  const look = lookOf(r)
  return {
    id: r.id,
    name: r.name,
    look,
    resolvedLook: isLook(r.sent_look) ? r.sent_look : resolveLook(ctx, look),
    content: parseContent(r.content),
    status: r.status,
    langs: r.langs ? JSON.parse(r.langs) as NewsletterLang[] : null,
    scheduledAt: iso(r.scheduled_at),
    startedAt: iso(r.started_at),
    sentAt: iso(r.sent_at),
    total: r.total,
    sent: r.sent_count,
    failed: r.failed_count,
    queued: r.status === 'sending' ? queuedCount(ctx, r.id) : 0,
    rev: r.rev,
    createdAt: new Date(r.created_at).toISOString(),
    createdBy: actorRef(ctx, r.created_by),
    updatedAt: new Date(r.updated_at).toISOString(),
    updatedBy: actorRef(ctx, r.updated_by),
  }
}

function row(ctx: AppContext, id: string): CampaignRow {
  const r = one<CampaignRow>(ctx.db, 'SELECT * FROM newsletter_campaigns WHERE id = ?', id)
  if (!r) throw notFound('campaign_not_found', 'Campaign not found')
  return r
}

export function getCampaign(ctx: AppContext, id: string): CampaignView {
  return viewOf(ctx, row(ctx, id))
}

export function listCampaigns(ctx: AppContext, status?: CampaignStatus): CampaignListItem[] {
  const rows = status
    ? all<CampaignRow>(ctx.db, 'SELECT * FROM newsletter_campaigns WHERE status = ? ORDER BY updated_at DESC LIMIT 500', status)
    : all<CampaignRow>(ctx.db, 'SELECT * FROM newsletter_campaigns ORDER BY updated_at DESC LIMIT 500')
  return rows.map((r) => {
    const { content, ...rest } = viewOf(ctx, r)
    return { ...rest, subject: pickLang(content.subject, 'de') }
  })
}

// ---------------------------------------------------------------- Schreiben

const EDITABLE: ReadonlySet<CampaignStatus> = new Set(['draft', 'scheduled', 'cancelled'])

function newId(): string {
  return randomBytes(12).toString('base64url')
}

/** Update-Bausteine ohne Punkte aus dem Changelog füllen (z. B. wenn Claude nur `{type:'update', version}` schickt). */
export async function hydrate(content: CampaignContent, loadUpdate: (version: string) => Promise<BlogPost | null>): Promise<CampaignContent> {
  const blocks: Block[] = []
  for (const b of content.blocks) {
    if (b.type === 'update' && !NEWSLETTER_LANGS.some((l) => b.items[l]?.length)) {
      const post = await loadUpdate(b.version).catch(() => null)
      if (!post) throw badRequest('unknown_version', `Version ${b.version} is not in the changelog`)
      const filled = updateBlockFromPost(post, b.id)
      blocks.push({ ...filled, ...stripUndefined({ button: b.button, url: b.url }) })
    } else blocks.push(b)
  }
  return { ...content, blocks }
}

function stripUndefined<T extends Record<string, unknown>>(o: T): Partial<T> {
  return Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined)) as Partial<T>
}

/** Punkte „- **Schlagzeile.** Satz …“ aus dem Changelog-Text → Update-Punkte (ohne Markdown). */
export function changelogItems(markdown: string): UpdateItem[] {
  const out: UpdateItem[] = []
  for (const m of markdown.matchAll(/^- \*\*(.+?)\*\*[ \t]*(.*)$/gm)) {
    const title = m[1]!.replace(/[.!:]$/, '').trim()
    const text = m[2]!.replace(/\[([^\]]+)\]\([^)]*\)/g, '$1').replace(/[*_`]/g, '').trim()
    if (title) out.push({ title: title.slice(0, 200), text: text.slice(0, 600) })
    if (out.length >= LIM.updateItems) break
  }
  return out
}

export function updateBlockFromPost(post: BlogPost, id: string): Extract<Block, { type: 'update' }> {
  const en = changelogItems(post.markdown.en)
  const de = changelogItems(post.markdown.de)
  return {
    id,
    type: 'update',
    version: post.version,
    ...(post.title ? { title: { en: post.title.en, de: post.title.de } } : {}),
    accent: post.banner?.accent ?? null,
    motif: post.banner?.motif ?? null,
    items: { ...(de.length ? { de } : {}), ...(en.length ? { en } : {}) },
  }
}

export function createCampaign(ctx: AppContext, a: NewsletterActor, input: z.output<typeof createBody>): CampaignView {
  assertActorCan(a, 'newsletter.manage')
  const n = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM newsletter_campaigns')!.n
  if (n >= MAX_CAMPAIGNS) throw conflict('too_many_campaigns', `At most ${MAX_CAMPAIGNS} campaigns – delete old drafts`)
  const t = ctx.now()
  const content = input.content ?? { subject: {}, preheader: {}, blocks: [] }
  const id = newId()
  run(
    ctx.db,
    `INSERT INTO newsletter_campaigns (id, name, look, content, status, rev, created_at, created_by, updated_at, updated_by)
     VALUES (?, ?, ?, ?, 'draft', 1, ?, ?, ?, ?)`,
    id, input.name ?? (pickLang(content.subject, 'de') || 'Newsletter'), input.look ?? 'auto', JSON.stringify(content), t, actorId(a), t, actorId(a),
  )
  audit(ctx, actorId(a), 'newsletter.campaign.create', null, input.name, `n${id}`)
  return getCampaign(ctx, id)
}

export function updateCampaign(ctx: AppContext, a: NewsletterActor, id: string, input: z.output<typeof patchBody>): CampaignView {
  assertActorCan(a, 'newsletter.manage')
  return tx(ctx.db, () => {
    const r = row(ctx, id)
    if (!EDITABLE.has(r.status)) throw conflict('campaign_locked', 'This campaign is being sent or was sent and cannot be changed')
    if (r.rev !== input.rev) throw new ApiError(409, 'stale', 'The campaign was changed in the meantime', { current: viewOf(ctx, r) })
    run(
      ctx.db,
      'UPDATE newsletter_campaigns SET name = ?, look = ?, content = ?, rev = rev + 1, updated_at = ?, updated_by = ? WHERE id = ?',
      input.name ?? r.name, input.look ?? r.look, input.content ? JSON.stringify(input.content) : r.content, ctx.now(), actorId(a), id,
    )
    audit(ctx, actorId(a), 'newsletter.campaign.update', null, r.name, `n${id}`)
    return getCampaign(ctx, id)
  })
}

export function duplicateCampaign(ctx: AppContext, a: NewsletterActor, id: string): CampaignView {
  const r = row(ctx, id)
  const content = parseContent(r.content)
  // Codes nicht mitnehmen (sie gehören zur alten Kampagne).
  content.blocks = content.blocks.map((b) => (b.type === 'cosmetic' ? { ...b, code: null, codeId: null, codeExpiresAt: null } : b))
  return createCampaign(ctx, a, { name: `${r.name} (2)`.slice(0, LIM.name), look: lookOf(r), content })
}

export function deleteCampaign(ctx: AppContext, a: NewsletterActor, id: string): void {
  assertActorCan(a, 'newsletter.manage')
  const r = row(ctx, id)
  if (r.status === 'scheduled' || r.status === 'sending') throw conflict('campaign_active', 'Cancel the campaign first')
  run(ctx.db, 'DELETE FROM newsletter_campaigns WHERE id = ?', id)
  audit(ctx, actorId(a), 'newsletter.campaign.delete', null, r.name, `n${id}`)
}

function assertSendable(r: CampaignRow): CampaignContent {
  const c = parseContent(r.content)
  if (c.blocks.length === 0) throw badRequest('campaign_empty', 'The campaign has no blocks')
  if (!NEWSLETTER_LANGS.some((l) => c.subject[l]?.trim())) throw badRequest('subject_missing', 'The campaign needs a subject')
  return c
}

function validLangs(langs: NewsletterLang[] | null | undefined): NewsletterLang[] | null {
  if (!langs || langs.length === 0 || langs.length === NEWSLETTER_LANGS.length) return null
  return NEWSLETTER_LANGS.filter((l) => langs.includes(l))
}

export function scheduleCampaign(ctx: AppContext, a: NewsletterActor, id: string, input: z.output<typeof scheduleBody>): CampaignView {
  assertActorCan(a, 'newsletter.send')
  const at = Date.parse(input.at)
  if (at <= ctx.now() + 30_000) throw badRequest('invalid_time', 'The time must be in the future')
  if (at > ctx.now() + SCHEDULE_MAX_MS) throw badRequest('invalid_time', 'At most 90 days ahead')
  return tx(ctx.db, () => {
    const r = row(ctx, id)
    if (!EDITABLE.has(r.status)) throw conflict('campaign_locked', 'This campaign is being sent or was sent')
    assertSendable(r)
    const langs = validLangs(input.langs)
    run(
      ctx.db,
      "UPDATE newsletter_campaigns SET status = 'scheduled', scheduled_at = ?, langs = ?, rev = rev + 1, updated_at = ?, updated_by = ? WHERE id = ?",
      at, langs ? JSON.stringify(langs) : null, ctx.now(), actorId(a), id,
    )
    audit(ctx, actorId(a), 'newsletter.campaign.schedule', null, `${r.name} @ ${new Date(at).toISOString()}${langs ? ` (${langs.join(',')})` : ''}`, `n${id}`)
    return getCampaign(ctx, id)
  })
}

export function sendCampaign(ctx: AppContext, a: NewsletterActor, id: string, input: z.output<typeof sendBody>, audience: (langs: NewsletterLang[] | null) => number): { campaign: CampaignView, queued: number } {
  assertActorCan(a, 'newsletter.send')
  requireMail(ctx)
  const r = row(ctx, id)
  if (!EDITABLE.has(r.status)) throw conflict('campaign_locked', 'This campaign is being sent or was sent')
  assertSendable(r)
  const langs = validLangs(input.langs === undefined ? (r.langs ? JSON.parse(r.langs) as NewsletterLang[] : null) : input.langs)
  const count = audience(langs)
  if (input.expectedCount !== undefined && input.expectedCount !== count) {
    throw new ApiError(409, 'audience_changed', 'The number of recipients changed – check and confirm again', { count })
  }
  run(ctx.db, "UPDATE newsletter_campaigns SET status = 'draft', langs = ?, updated_at = ?, updated_by = ? WHERE id = ?", langs ? JSON.stringify(langs) : null, ctx.now(), actorId(a), id)
  const queued = startCampaign(ctx, id)
  audit(ctx, actorId(a), 'newsletter.campaign.send', null, `${r.name}: ${queued} recipients${langs ? ` (${langs.join(',')})` : ''}`, `n${id}`)
  return { campaign: getCampaign(ctx, id), queued }
}

export function cancelCampaign(ctx: AppContext, a: NewsletterActor, id: string): CampaignView {
  assertActorCan(a, 'newsletter.send')
  return tx(ctx.db, () => {
    const r = row(ctx, id)
    if (r.status !== 'scheduled' && r.status !== 'sending') throw conflict('campaign_not_active', 'Only scheduled or running campaigns can be cancelled')
    const dropped = r.status === 'sending' ? cancelQueued(ctx, id) : 0
    run(ctx.db, "UPDATE newsletter_campaigns SET status = 'cancelled', rev = rev + 1, updated_at = ?, updated_by = ? WHERE id = ?", ctx.now(), actorId(a), id)
    audit(ctx, actorId(a), 'newsletter.campaign.cancel', null, `${r.name}${dropped ? `: ${dropped} not sent` : ''}`, `n${id}`)
    return getCampaign(ctx, id)
  })
}

// ---------------------------------------------------------------- Vorschau + Test

export function previewContent(ctx: AppContext, content: CampaignContent, look: (typeof CAMPAIGN_LOOKS)[number], lang: NewsletterLang): RenderedMail & { look: Look } {
  const l = resolveLook(ctx, look)
  const m = renderCampaign(ctx, content, l, lang)
  const dummy = `${ctx.config.siteUrl}/newsletter?lang=${lang}`
  return { ...m, html: m.html.split(UNSUB_PLACEHOLDER).join(dummy), text: m.text.split(UNSUB_PLACEHOLDER).join(dummy), look: l }
}

export function previewCampaign(ctx: AppContext, id: string, input: z.output<typeof savedPreviewBody>): RenderedMail & { look: Look } {
  const r = row(ctx, id)
  const look = input.look ?? (isLook(r.sent_look) ? r.sent_look : lookOf(r))
  return previewContent(ctx, parseContent(r.content), look, input.lang)
}

/** Testmail an die eigene Adresse (Team: bestätigte Benachrichtigungs-Adresse; Schlüssel: hinterlegte Test-Adresse bzw. MAIL_TEAM_TO). */
export function testCampaign(ctx: AppContext, a: NewsletterActor, id: string, input: z.output<typeof testBody>): { to: string } {
  assertActorCan(a, 'newsletter.manage')
  requireMail(ctx)
  const r = row(ctx, id)
  const to = a.kind === 'staff'
    ? one<{ email: string | null }>(ctx.db, 'SELECT email FROM mail_team_prefs WHERE uuid = ?', a.staff.uuid)?.email ?? null
    : a.testEmail ?? ctx.config.mail?.teamTo[0] ?? null
  if (!to) throw conflict('no_test_address', 'Set and confirm your own e-mail address under Newsletter → Notifications first')
  const look = resolveLook(ctx, input.look ?? lookOf(r))
  const m = renderCampaign(ctx, parseContent(r.content), look, input.lang, { test: true })
  const dummy = `${ctx.config.siteUrl}/newsletter?lang=${input.lang}`
  enqueue(ctx, { kind: 'test', to, campaignId: id, mail: { ...m, html: m.html.split(UNSUB_PLACEHOLDER).join(dummy), text: m.text.split(UNSUB_PLACEHOLDER).join(dummy) } })
  audit(ctx, actorId(a), 'newsletter.campaign.test', null, `${r.name} (${input.lang}, ${look})`, `n${id}`)
  return { to: maskEmail(to) }
}

// ---------------------------------------------------------------- Code erzeugen (Kosmetik-Karte)

export function generateBlockCode(ctx: AppContext, a: NewsletterActor, id: string, input: z.output<typeof codeBody>): CampaignView {
  assertActorCan(a, 'newsletter.manage')
  const r = row(ctx, id)
  if (!EDITABLE.has(r.status)) throw conflict('campaign_locked', 'This campaign is being sent or was sent')
  if (r.rev !== input.rev) throw new ApiError(409, 'stale', 'The campaign was changed in the meantime', { current: viewOf(ctx, r) })
  const content = parseContent(r.content)
  const b = content.blocks.find((x) => x.id === input.blockId)
  if (!b || b.type !== 'cosmetic') throw notFound('block_not_found', 'Cosmetic block not found')
  if (!b.item) throw badRequest('item_missing', 'Pick a cape or cosmetic for the card first')
  if (b.code) throw conflict('code_exists', 'This card already has a code')
  if (input.expiresAt && Date.parse(input.expiresAt) > ctx.now() + 366 * 24 * 60 * 60 * 1000) throw badRequest('invalid_time', 'At most one year')
  const target = b.item.kind === 'cape' ? { capeId: b.item.id } : { cosmeticId: b.item.id }
  const { codes } = createCodes(ctx, actorId(a), { ...target, maxUses: input.maxUses, count: 1, expiresAt: input.expiresAt, note: `newsletter ${id}` })
  const code = codes[0]!
  b.code = code.code
  b.codeId = code.id
  b.codeExpiresAt = code.expiresAt
  run(ctx.db, 'UPDATE newsletter_campaigns SET content = ?, rev = rev + 1, updated_at = ?, updated_by = ? WHERE id = ?', JSON.stringify(content), ctx.now(), actorId(a), id)
  audit(ctx, actorId(a), 'newsletter.campaign.code', null, `${r.name}: code #${code.id} (${b.item.kind} ${b.item.id}, max ${input.maxUses})`, `n${id}`)
  return getCampaign(ctx, id)
}

// ---------------------------------------------------------------- Quellen für den Editor

export interface SourceItem {
  kind: 'cape' | 'cosmetic'
  id: string
  name: string
  image: string | null
  unlock: string
}

/** Teile für die Kosmetik-Karte (mitgelieferte, nicht ausgemustert). */
export function itemSources(ctx: AppContext): SourceItem[] {
  const site = ctx.config.siteUrl
  const capes = all<{ id: string, name: string, unlock: string, sha256: string }>(
    ctx.db, "SELECT id, name, unlock, sha256 FROM capes WHERE kind = 'builtin' AND retired = 0 AND status = 'approved' ORDER BY sort, id",
  ).map((c) => ({ kind: 'cape' as const, id: c.id, name: c.name, unlock: c.unlock, image: `${site}/v1/capes/${c.id}.png?v=${c.sha256.slice(0, 12)}` }))
  const cosmetics = all<{ id: string, name: string, unlock: string }>(
    ctx.db, "SELECT id, name, unlock FROM cosmetics WHERE kind = 'builtin' AND retired = 0 AND status = 'approved' AND slot <> 'emote' ORDER BY sort, id",
  ).map((c) => ({ kind: 'cosmetic' as const, id: c.id, name: c.name, unlock: c.unlock, image: null as string | null }))
  // Kartenbilder der v2-Teile (gleich wie in der Mail).
  for (const c of cosmetics) {
    const row = getCosmetic(ctx, c.id)
    if (row) c.image = itemImage(ctx, { kind: 'cosmetic', id: row.id })
  }
  return [...cosmetics, ...capes]
}

export function creatorSources(ctx: AppContext): { uuid: string, name: string, tier: string }[] {
  return all<{ uuid: string, name: string, tier: string }>(
    ctx.db, "SELECT c.uuid, u.name, c.tier FROM creators c JOIN users u ON u.uuid = c.uuid WHERE c.state = 'active' ORDER BY CASE c.tier WHEN 'top' THEN 0 WHEN 'partner' THEN 1 ELSE 2 END, u.name_lower",
  )
}

/** Prüft Bezüge vor dem Speichern (Creator aktiv, Teil vorhanden) – sonst 400 mit Baustein-ID. */
export function checkRefs(ctx: AppContext, content: CampaignContent): void {
  for (const b of content.blocks) {
    if (b.type === 'creator' && !creatorInfo(ctx, b.uuid)) throw badRequest('creator_not_found', `Block ${b.id}: no active creator with this UUID`)
    if (b.type === 'cosmetic' && b.item) {
      const ok = b.item.kind === 'cape' ? !!getCape(ctx, b.item.id) : !!getCosmetic(ctx, b.item.id)
      if (!ok) throw badRequest('item_not_found', `Block ${b.id}: ${b.item.kind} ${b.item.id} not found`)
    }
  }
}

export const BLOCK_TYPE_LIST = BLOCK_TYPES
