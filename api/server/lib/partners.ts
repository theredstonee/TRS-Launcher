import { randomBytes } from 'node:crypto'
import { existsSync, readFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { z } from 'zod'
import { audit } from './audit'
import { capeUrl, capeView, getCape, grantCape, writeAtomic, type CapeRow } from './capes'
import { isCreatorCodeTaken, ONETIME_ALPHABET, PARTNER_FIXED, PARTNER_ONETIME } from './code-space'
import type { AppContext } from './context'
import { cosmeticView, getCosmetic, grantCosmetic } from './cosmetics'
import { all, one, run, tx } from './db'
import { ApiError, badRequest, conflict, forbidden, notFound, tooMany, unauthorized } from './errors'
import { newUploadCapeId, normalizeUuid, sha256Hex } from './ids'
import { downscale } from './images'
import { logoPath, MC_VERSION, cleanMotd, serverJoinStats, type PartnerServerRow } from './partner-servers'
import { clearPartnerStatsCache, PARTNER_NEW_PLAYER_MS, partnerTrend, type PartnerPeriod, type PartnerStatDay } from './partner-stats'
import { deliveriesOf, queueWebhook, webhookView, type DeliveryView, type WebhookView } from './partner-webhooks'
import { decodeRgba, encodeRgba, inspectPng, sanitizeCapeUpload } from './png'
import { RULES } from './ratelimit'
import { serverAddress } from './schemas'
import { hatPreview, type PublicHat } from './site'
import { assertCan, can, teamOf, type Staff } from './team'
import { getUser, getUserByName } from './users'

/**
 * Partnerprogramm (API.md §44): Partner (nur vom Team angelegt), feste Codes + Einmal-Codes (Kontingent, Pakete,
 * Partner-API), Unterstützung (ein Partner je Spieler, unabhängig vom Creator), Partner-Kosmetik (Katalog-Teile, Quelle
 * `partner`), Mitglieder mit Rechten, Anträge ans Team, Werbe-Links, Partner-Server, Statistik. Kein Abzeichen.
 */

const MIN = 60_000
const DAY = 86_400_000
/** Wechsel zu einem ANDEREN Partner frühestens so lange nach dem letzten Start (wie §35.1). */
export const PARTNER_SWITCH_LOCK_MS = 30 * DAY
export { PARTNER_NEW_PLAYER_MS }
export const PARTNER_SLUG = /^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$/
/** Pfade unter /v1/partners/ und /partners/, die keine Partner sind. */
export const RESERVED_SLUGS = new Set(['servers', 'new', 'admin', 'api', 'go', 'dashboard', 'catalog', 'requests', 'stats'])
export const PARTNER_PREFIX = /^[A-Z]{2,4}$/
export const CATEGORIES = ['hosting', 'network', 'other'] as const
export type PartnerCategory = (typeof CATEGORIES)[number]
export const PARTNER_RIGHTS = ['stats', 'codes', 'api', 'links', 'server', 'webhook', 'promo', 'cosmetics', 'profile'] as const
export type PartnerRight = (typeof PARTNER_RIGHTS)[number]
export const TEXT_MAX = 1200
export const LINKS_MAX = 6
export const BATCH_MAX = 500
export const API_BATCH_MAX = 100
export const MEMBERS_MAX = 10
/** Feste Codes je Partner (Haupt-Code + weitere, auch deaktivierte). */
export const FIXED_CODES_MAX = 10
export const CAMPAIGNS_MAX = 20
export const CAMPAIGN = /^[a-z0-9-]{1,24}$/
export const QUOTA_PACKAGES = [500, 1000, 5000] as const
export const START_QUOTAS = [0, 500, 1000] as const
export const LOGO_SIZE = 128
export const LOGO_MAX_BYTES = 1024 * 1024
export const CAPE_MAX_BYTES = 20 * 1024 * 1024
/** Doppelte Klicks/Downloads derselben IP+UA innerhalb dieser Zeit zählen einmal (nur im RAM). */
export const COUNT_DEDUPE_MS = 10 * MIN
export const API_KEY = /^trsp_live_[A-Za-z0-9]{32}$/
const BASE62 = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'

export type PartnerState = 'active' | 'paused'
export type RequestKind = 'quota' | 'code_change' | 'profile' | 'logo' | 'server' | 'cape' | 'cosmetic_wish' | 'promo'
export type PromoChannel = 'blog' | 'newsletter' | 'wiki'
export type PartnerUpdateKind = 'request_accepted' | 'request_rejected' | 'quota_granted' | 'team_changed' | 'member_joined' | 'member_left' | 'paused' | 'resumed' | 'updated'

/** Nötiges Recht je Antragsart. */
const REQUEST_RIGHT: Record<RequestKind, PartnerRight> = {
  quota: 'codes',
  code_change: 'codes',
  profile: 'profile',
  logo: 'profile',
  server: 'server',
  cape: 'cosmetics',
  cosmetic_wish: 'cosmetics',
  promo: 'promo',
}

// ---------------------------------------------------------------- Zeilen

export interface PartnerRow {
  id: number
  slug: string
  name: string
  category: PartnerCategory
  state: PartnerState
  prefix: string
  owner_uuid: string | null
  text_de: string
  text_en: string
  links: string
  wiki_url: string | null
  logo_sha: string | null
  quota_total: number
  quota_used: number
  quota_low_sent: number
  /** Team-Schalter „Partner-Server erlaubt“ (Migration 52, Standard aus). */
  server_allowed: number
  created_at: number
  updated_at: number
}

interface CodeRow {
  id: number
  partner_id: number
  code: string
  kind: 'fixed' | 'onetime'
  batch_id: number | null
  ref: string | null
  redeemed_by: string | null
  redeemed_at: number | null
  blocked: number
  main: number
  created_at: number
}

interface BatchRow {
  id: number
  partner_id: number
  label: string
  via: 'dashboard' | 'api'
  count: number
  created_at: number
  created_by: string | null
  blocked_at: number | null
}

interface SupportRow {
  uuid: string
  partner_id: number | null
  last_partner_id: number | null
  last_started_at: number | null
  updated_at: number
}

interface MemberRow {
  partner_id: number
  uuid: string
  rights: string
  status: 'pending' | 'active'
  created_at: number
  accepted_at: number | null
}

interface RequestRow {
  id: number
  partner_id: number
  kind: RequestKind
  channel: PromoChannel | null
  status: 'open' | 'accepted' | 'rejected' | 'withdrawn'
  data: string
  reason: string | null
  created_at: number
  created_by: string | null
  decided_at: number | null
  decided_by: string | null
}

// ---------------------------------------------------------------- Sichten

export interface PartnerLink {
  label: string
  url: string
}

export interface PartnerCosmeticRef {
  kind: 'cape' | 'cosmetic'
  id: string
  name: string
  previewUrl: string | null
}

export interface PartnerCard {
  slug: string
  name: string
  category: PartnerCategory
  logo: string | null
  text: { de: string, en: string }
  links: PartnerLink[]
  code: string | null
  /** Aktive feste Codes, Haupt-Code zuerst. */
  codes: string[]
  server: { name: string, address: string } | null
  wikiUrl: string | null
  supporters: number
  cosmetics: PartnerCosmeticRef[]
}

/** Fester Code mit Einlösungen (Dashboard mit Recht `codes`, Team). */
export interface FixedCodeView {
  code: string
  main: boolean
  active: boolean
  redemptions: number
  redemptions30d: number
  createdAt: string
}

export interface PartnerRef {
  slug: string
  name: string
}

const iso = (t: number) => new Date(t).toISOString()
const isoOrNull = (t: number | null) => (t === null ? null : iso(t))
const dayOf = (t: number) => new Date(t).toISOString().slice(0, 10)

function parse<S extends z.ZodType>(schema: S, raw: unknown): z.output<S> {
  const r = schema.safeParse(raw)
  if (!r.success) {
    throw badRequest('invalid_request', 'Request validation failed', {
      fields: r.error.issues.slice(0, 20).map((i) => ({ path: i.path.join('.') || '(body)', message: i.message })),
    })
  }
  return r.data
}

function parseJson<T>(raw: string, fallback: T): T {
  try {
    return JSON.parse(raw) as T
  } catch {
    return fallback
  }
}

function parseLinks(raw: string): PartnerLink[] {
  const v = parseJson<unknown>(raw, [])
  return Array.isArray(v) ? (v as PartnerLink[]).filter((l) => l && typeof l.label === 'string' && typeof l.url === 'string') : []
}

function parseRights(raw: string): PartnerRight[] {
  const v = parseJson<unknown>(raw, [])
  return Array.isArray(v) ? PARTNER_RIGHTS.filter((r) => v.includes(r)) : []
}

const rightsJson = (rights: readonly PartnerRight[]) => JSON.stringify(PARTNER_RIGHTS.filter((r) => rights.includes(r)))

// ---------------------------------------------------------------- Schemas

/** Mehrzeiliger reiner Text ohne Steuerzeichen (außer Zeilenumbruch). */
const multiline = (max: number) =>
  z
    .string()
    .trim()
    .max(max)
    .transform((s) => s.replace(/\r\n?/g, '\n'))
    .refine((s) => !/[\p{Cc}\p{Cf}\p{Co}\p{Cn}]/u.test(s.replace(/\n/g, '')), 'must not contain control characters')

const oneLine = (min: number, max: number) =>
  z
    .string()
    .trim()
    .min(min)
    .max(max)
    .refine((s) => !/[\p{Cc}\p{Cf}\p{Co}\p{Cn}]/u.test(s), 'must not contain control characters')

export const httpsUrl = z
  .string()
  .trim()
  .max(300)
  .refine((s) => {
    try {
      const u = new URL(s)
      return u.protocol === 'https:' && !u.username && !u.password && !!u.hostname
    } catch {
      return false
    }
  }, 'must be an https URL')

const linkSchema = z.strictObject({ label: oneLine(1, 40), url: httpsUrl })
const linksSchema = z.array(linkSchema).max(LINKS_MAX)
const textSchema = z.strictObject({ de: multiline(TEXT_MAX), en: multiline(TEXT_MAX) })
const versionSchema = z.string().trim().regex(MC_VERSION, 'must be a Minecraft version like 1.21.11')
const slugSchema = z.string().trim().toLowerCase().regex(PARTNER_SLUG, 'invalid slug').refine((s) => !RESERVED_SLUGS.has(s), 'reserved')
const fixedCodeSchema = z.string().trim().toUpperCase().regex(PARTNER_FIXED, '4–19 characters A–Z and 0–9')
const prefixSchema = z.string().trim().toUpperCase().regex(PARTNER_PREFIX, '2–4 letters A–Z')
const rightsSchema = z.array(z.enum(PARTNER_RIGHTS)).max(PARTNER_RIGHTS.length)

export const serverRequestSchema = z
  .strictObject({
    name: oneLine(1, 40),
    address: serverAddress.transform((s) => s.toLowerCase()),
    minVersion: versionSchema,
    maxVersion: versionSchema,
    motd: oneLine(0, 120).transform(cleanMotd),
  })

export const requestBody = z.discriminatedUnion('kind', [
  z.strictObject({ kind: z.literal('quota'), amount: z.union([z.literal(500), z.literal(1000), z.literal(5000)]) }),
  // `add` = weiterer fester Code, sonst wird der Haupt-Code geändert.
  z.strictObject({ kind: z.literal('code_change'), code: fixedCodeSchema, action: z.enum(['change', 'add']).default('change') }),
  z.strictObject({ kind: z.literal('profile'), text: textSchema, links: linksSchema }),
  serverRequestSchema.extend({ kind: z.literal('server') }),
  // Partner-Umhang aus den eigenen Uploads (§5.6) des Inhabers oder eines Mitglieds wählen.
  z.strictObject({ kind: z.literal('cape'), capeId: z.string().trim().min(1).max(40), name: oneLine(1, 32).optional() }),
  z.strictObject({ kind: z.literal('cosmetic_wish'), text: multiline(1000).refine((s) => s.length > 0, 'must not be empty') }),
  z.strictObject({
    kind: z.literal('promo'),
    channel: z.enum(['blog', 'newsletter', 'wiki']),
    topic: oneLine(1, 120),
    text: multiline(2000).refine((s) => s.length > 0, 'must not be empty'),
  }),
])

export const batchBody = z.strictObject({ label: oneLine(1, 60), count: z.number().int().min(1).max(BATCH_MAX) })
export const apiCodesBody = z.strictObject({
  count: z.number().int().min(1).max(API_BATCH_MAX),
  label: oneLine(1, 60).optional(),
  ref: oneLine(1, 64).optional(),
})
export const campaignBody = z.strictObject({ campaign: z.string().trim().toLowerCase().regex(CAMPAIGN, '1–24 characters a–z, 0–9 and -') })
export const memberInviteBody = z
  .strictObject({ uuid: z.string().trim().max(36).optional(), name: z.string().trim().max(16).optional(), rights: rightsSchema.min(1) })
  .refine((o) => !!o.uuid || !!o.name, 'uuid or name is required')
export const memberRightsBody = z.strictObject({ rights: rightsSchema.min(1) })
export const inviteAnswerBody = z.strictObject({ accept: z.boolean() })
export const capeQuery = z.strictObject({
  name: oneLine(1, 32).optional(),
  frames: z.coerce.number().int().min(1).max(32).optional(),
  frameTimeMs: z.coerce.number().int().min(50).max(10_000).optional(),
})

export const adminCreateBody = z.strictObject({
  name: oneLine(1, 40),
  slug: slugSchema,
  category: z.enum(CATEGORIES),
  code: fixedCodeSchema,
  prefix: prefixSchema,
  owner: z.string().trim().min(1).max(36),
  quota: z.union([z.literal(0), z.literal(500), z.literal(1000)]).default(0),
  serverAllowed: z.boolean().default(false),
})
export const adminUpdateBody = z
  .strictObject({
    name: oneLine(1, 40).optional(),
    category: z.enum(CATEGORIES).optional(),
    state: z.enum(['active', 'paused']).optional(),
    code: fixedCodeSchema.optional(),
    prefix: prefixSchema.optional(),
    owner: z.string().trim().min(1).max(36).optional(),
    wikiUrl: httpsUrl.nullable().optional(),
    text: textSchema.optional(),
    links: linksSchema.optional(),
    serverAllowed: z.boolean().optional(),
  })
  .refine((o) => Object.keys(o).length > 0, 'at least one field is required')
export const fixedCodeBody = z.strictObject({ code: fixedCodeSchema, main: z.boolean().default(false) })
export const fixedCodePatchBody = z
  .strictObject({ active: z.boolean().optional(), main: z.literal(true).optional() })
  .refine((o) => o.active !== undefined || o.main !== undefined, 'active or main is required')
export const quotaBody = z.strictObject({ amount: z.union([z.literal(500), z.literal(1000), z.literal(5000)]) })
export const itemBody = z.strictObject({ kind: z.enum(['cape', 'cosmetic']), id: z.string().trim().min(1).max(40) })
export const decisionBody = z.strictObject({
  status: z.enum(['accepted', 'rejected']),
  reason: oneLine(1, 300).optional(),
})
export const requestListQuery = z.strictObject({ status: z.enum(['open', 'accepted', 'rejected', 'withdrawn']).optional() })
export const serverHiddenBody = z.strictObject({ hidden: z.boolean() })

// ---------------------------------------------------------------- Grundlagen

export function partnerFileDir(ctx: AppContext): string {
  return join(ctx.config.dataDir, 'partner-files')
}

function partnerBySlug(ctx: AppContext, slug: string): PartnerRow | undefined {
  return one<PartnerRow>(ctx.db, 'SELECT * FROM partners WHERE slug = ?', slug.toLowerCase())
}

function partnerById(ctx: AppContext, id: number): PartnerRow | undefined {
  return one<PartnerRow>(ctx.db, 'SELECT * FROM partners WHERE id = ?', id)
}

function requirePartner(ctx: AppContext, slug: string): PartnerRow {
  const p = partnerBySlug(ctx, slug)
  if (!p) throw notFound('partner_not_found', 'Partner not found')
  return p
}

function fixedCode(ctx: AppContext, partnerId: number): string | null {
  return one<{ code: string }>(ctx.db, "SELECT code FROM partner_codes WHERE partner_id = ? AND kind = 'fixed' AND main = 1", partnerId)?.code ?? null
}

function activeFixedCodes(ctx: AppContext, partnerId: number): string[] {
  return all<{ code: string }>(
    ctx.db,
    "SELECT code FROM partner_codes WHERE partner_id = ? AND kind = 'fixed' AND blocked = 0 ORDER BY main DESC, created_at, id",
    partnerId,
  ).map((r) => r.code)
}

function fixedCodeViews(ctx: AppContext, partnerId: number): FixedCodeView[] {
  const since = ctx.now() - 30 * DAY
  return all<{ code: string, main: number, blocked: number, created_at: number, total: number, recent: number }>(
    ctx.db,
    `SELECT k.code, k.main, k.blocked, k.created_at,
       (SELECT COUNT(*) FROM partner_redemptions r WHERE r.code_id = k.id) AS total,
       (SELECT COUNT(*) FROM partner_redemptions r WHERE r.code_id = k.id AND r.at >= ?) AS recent
     FROM partner_codes k WHERE k.partner_id = ? AND k.kind = 'fixed' ORDER BY k.main DESC, k.created_at, k.id`,
    since, partnerId,
  ).map((r) => ({ code: r.code, main: r.main === 1, active: r.blocked === 0, redemptions: r.total, redemptions30d: r.recent, createdAt: iso(r.created_at) }))
}

function fixedCodeRow(ctx: AppContext, partnerId: number, code: string): CodeRow | undefined {
  return one<CodeRow>(ctx.db, "SELECT * FROM partner_codes WHERE partner_id = ? AND kind = 'fixed' AND code = ?", partnerId, code)
}

function checkFixedCodeRoom(ctx: AppContext, partnerId: number, code: string): void {
  if (fixedCodeRow(ctx, partnerId, code)) throw conflict('code_taken', 'This code is already taken')
  const n = one<{ n: number }>(ctx.db, "SELECT COUNT(*) AS n FROM partner_codes WHERE partner_id = ? AND kind = 'fixed'", partnerId)!.n
  if (n >= FIXED_CODES_MAX) throw forbidden('partner_code_limit', `At most ${FIXED_CODES_MAX} fixed codes per partner`)
}

/** Weiteren festen Code anlegen (Namensraum vorher geprüft). */
function insertFixedCode(ctx: AppContext, partnerId: number, code: string, t: number): void {
  checkFixedCodeRoom(ctx, partnerId, code)
  run(ctx.db, "INSERT INTO partner_codes (partner_id, code, kind, main, created_at) VALUES (?, ?, 'fixed', 0, ?)", partnerId, code, t)
}

/**
 * Haupt-Code setzen: ein eigener fester Code wird (aktiv) zum Haupt-Code, sonst bekommt der Haupt-Code den neuen Text
 * (seine Einlösungen bleiben).
 */
function setMainCode(ctx: AppContext, partnerId: number, code: string, t: number): void {
  const own = fixedCodeRow(ctx, partnerId, code)
  if (own) {
    run(ctx.db, "UPDATE partner_codes SET main = 0 WHERE partner_id = ? AND kind = 'fixed' AND main = 1 AND id <> ?", partnerId, own.id)
    run(ctx.db, 'UPDATE partner_codes SET main = 1, blocked = 0 WHERE id = ?', own.id)
  } else if (run(ctx.db, "UPDATE partner_codes SET code = ? WHERE partner_id = ? AND kind = 'fixed' AND main = 1", code, partnerId) === 0) {
    run(ctx.db, "INSERT INTO partner_codes (partner_id, code, kind, main, created_at) VALUES (?, ?, 'fixed', 1, ?)", partnerId, code, t)
  }
}

function supporterCount(ctx: AppContext, partnerId: number): number {
  return one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM partner_support WHERE partner_id = ?', partnerId)!.n
}

function nameOf(ctx: AppContext, uuid: string | null): string {
  return uuid ? getUser(ctx, uuid)?.name ?? '' : ''
}

export function partnerRef(p: Pick<PartnerRow, 'slug' | 'name'>): PartnerRef {
  return { slug: p.slug, name: p.name }
}

/** Zugeordnete Katalog-Teile (ausgemusterte/fehlende fallen weg). */
function itemsOf(ctx: AppContext, partnerId: number): PartnerCosmeticRef[] {
  const out: PartnerCosmeticRef[] = []
  for (const r of all<{ kind: 'cape' | 'cosmetic', item_id: string }>(ctx.db, 'SELECT kind, item_id FROM partner_items WHERE partner_id = ? ORDER BY created_at, item_id', partnerId)) {
    if (r.kind === 'cape') {
      const c = getCape(ctx, r.item_id)
      if (c && !c.retired && c.status === 'approved') out.push({ kind: 'cape', id: c.id, name: c.name, previewUrl: capeUrl(ctx, c) })
    } else {
      const c = getCosmetic(ctx, r.item_id)
      if (c && !c.retired && c.status === 'approved') out.push({ kind: 'cosmetic', id: c.id, name: c.name, previewUrl: cosmeticView(ctx, c).texture?.url ?? null })
    }
  }
  return out
}

function liveServer(ctx: AppContext, partnerId: number): PartnerServerRow | undefined {
  return one<PartnerServerRow>(ctx.db, 'SELECT * FROM partner_servers WHERE partner_id = ?', partnerId)
}

export function partnerCard(ctx: AppContext, p: PartnerRow): PartnerCard {
  const s = liveServer(ctx, p.id)
  return {
    slug: p.slug,
    name: p.name,
    category: p.category,
    logo: logoPath(p.slug, p.logo_sha),
    text: { de: p.text_de, en: p.text_en },
    links: parseLinks(p.links),
    code: fixedCode(ctx, p.id),
    codes: activeFixedCodes(ctx, p.id),
    server: s && !s.hidden && p.server_allowed === 1 ? { name: s.name, address: s.address } : null,
    wikiUrl: p.wiki_url,
    supporters: supporterCount(ctx, p.id),
    cosmetics: itemsOf(ctx, p.id),
  }
}

// ---------------------------------------------------------------- Öffentlich

export function listPartners(ctx: AppContext): { partners: PartnerCard[] } {
  return {
    partners: all<PartnerRow>(ctx.db, "SELECT * FROM partners WHERE state = 'active' ORDER BY name COLLATE NOCASE, id").map((p) => partnerCard(ctx, p)),
  }
}

export function publicPartner(ctx: AppContext, slug: string): PartnerCard {
  const p = partnerBySlug(ctx, slug)
  if (!p || p.state !== 'active') throw notFound('partner_not_found', 'Partner not found')
  return partnerCard(ctx, p)
}

/** Logo (öffentlich nur für aktive Partner). */
export function readLogo(ctx: AppContext, slug: string, sha?: string): { png: Buffer, sha256: string, public: boolean } {
  const p = partnerBySlug(ctx, slug)
  const want = sha ?? p?.logo_sha
  if (!p || p.state !== 'active' || !want || !/^[0-9a-f]{64}$/.test(want)) throw notFound('logo_not_found', 'Logo not found')
  const file = join(partnerFileDir(ctx), `${want}.png`)
  if (!existsSync(file)) throw notFound('logo_not_found', 'Logo not found')
  return { png: readFileSync(file), sha256: want, public: true }
}

// ---------------------------------------------------------------- Bilder

/** Logo säubern: PNG, quadratisch (≥ 32 px), neu kodiert auf 128×128. */
export function sanitizeLogo(buf: Buffer): Buffer {
  if (buf.length > LOGO_MAX_BYTES) throw new ApiError(413, 'payload_too_large', 'Logo must be at most 1 MB')
  const { header } = inspectPng(buf)
  if (header.width !== header.height) throw badRequest('invalid_logo', 'The logo must be square')
  if (header.width < 32 || header.width > 2048) throw badRequest('invalid_logo', 'The logo must be 32 to 2048 pixels wide')
  const img = decodeRgba(buf, 2048 * 2048)
  let src = { width: img.width, height: img.height, data: new Uint8Array(img.data) }
  if (src.width > LOGO_SIZE) src = downscale(src, LOGO_SIZE) as typeof src
  const out = Buffer.alloc(LOGO_SIZE * LOGO_SIZE * 4)
  for (let y = 0; y < LOGO_SIZE; y++) {
    for (let x = 0; x < LOGO_SIZE; x++) {
      const sx = Math.min(src.width - 1, Math.floor((x * src.width) / LOGO_SIZE))
      const sy = Math.min(src.height - 1, Math.floor((y * src.height) / LOGO_SIZE))
      const i = (sy * src.width + sx) * 4
      const o = (y * LOGO_SIZE + x) * 4
      const a = src.data[i + 3]!
      if (a === 0) continue
      out[o] = src.data[i]!
      out[o + 1] = src.data[i + 1]!
      out[o + 2] = src.data[i + 2]!
      out[o + 3] = a
    }
  }
  return encodeRgba(LOGO_SIZE, LOGO_SIZE, out)
}

function storeFile(ctx: AppContext, png: Buffer): string {
  const sha = sha256Hex(png)
  writeAtomic(partnerFileDir(ctx), `${sha}.png`, png)
  return sha
}

/** Datei löschen, wenn sie weder Logo noch Teil eines offenen Antrags ist. */
function dropFileIfUnused(ctx: AppContext, sha: string | undefined): void {
  if (!sha || !/^[0-9a-f]{64}$/.test(sha)) return
  if (one(ctx.db, 'SELECT 1 AS x FROM partners WHERE logo_sha = ?', sha)) return
  if (one(ctx.db, "SELECT 1 AS x FROM partner_requests WHERE status = 'open' AND kind IN ('logo', 'cape') AND json_extract(data, '$.sha') = ?", sha)) return
  rmSync(join(partnerFileDir(ctx), `${sha}.png`), { force: true })
}

// ---------------------------------------------------------------- Ereignisse

/** `partner_updated` an Inhaber und aktive Mitglieder (Launcher/Website laden neu). */
export function notifyMembers(ctx: AppContext, p: PartnerRow, kind: PartnerUpdateKind, extra: { action?: string, reason?: string, requestKind?: RequestKind } = {}): void {
  const to = new Set<string>()
  if (p.owner_uuid) to.add(p.owner_uuid)
  for (const m of all<{ uuid: string }>(ctx.db, "SELECT uuid FROM partner_members WHERE partner_id = ? AND status = 'active'", p.id)) to.add(m.uuid)
  for (const uuid of to) {
    ctx.events.publish(uuid, {
      type: 'partner_updated',
      slug: p.slug,
      kind,
      ...(extra.action ? { action: extra.action } : {}),
      ...(extra.reason ? { reason: extra.reason } : {}),
      ...(extra.requestKind ? { request: extra.requestKind } : {}),
    }, { meOnly: true })
  }
}

function notifySupport(ctx: AppContext, uuid: string, p: PartnerRow | undefined | null): void {
  ctx.events.publish(uuid, { type: 'partner_support_changed', partner: p ? partnerRef(p) : null }, { meOnly: true })
}

function bumpDaily(ctx: AppContext, partnerId: number, field: 'fixed' | 'onetime' | 'new_supporters' | 'left_count'): void {
  run(
    ctx.db,
    `INSERT INTO partner_daily (partner_id, day, ${field}) VALUES (?, ?, 1)
     ON CONFLICT(partner_id, day) DO UPDATE SET ${field} = ${field} + 1`,
    partnerId, dayOf(ctx.now()),
  )
}

// ---------------------------------------------------------------- Einlösen + Unterstützung

function supportRow(ctx: AppContext, uuid: string): SupportRow | undefined {
  return one<SupportRow>(ctx.db, 'SELECT * FROM partner_support WHERE uuid = ?', uuid)
}

/** Ende der 30-Tage-Sperre für einen Wechsel zu einem ANDEREN Partner als dem zuletzt begonnenen (oder `null`). */
function switchLockUntil(ctx: AppContext, s: SupportRow | undefined): number | null {
  if (!s?.last_partner_id || s.last_started_at === null) return null
  const until = s.last_started_at + PARTNER_SWITCH_LOCK_MS
  if (until <= ctx.now()) return null
  // Pausierte (oder gelöschte) Partner halten niemanden fest.
  const last = partnerById(ctx, s.last_partner_id)
  return last && last.state === 'active' ? until : null
}

export interface RedeemPartnerResult {
  kind: 'partner'
  partner: PartnerCard
  supportChanged: boolean
  switchAvailableAt: string | null
  granted: { capes: { capeId: string, name: string }[], cosmetics: { id: string, name: string, slot: string }[] }
  oneTime: boolean
}

/** Alle zugeordneten Teile geben (Quelle `partner`); Liste der Teile, die der Spieler danach hat. */
function grantItems(ctx: AppContext, uuid: string, partnerId: number): RedeemPartnerResult['granted'] {
  const out: RedeemPartnerResult['granted'] = { capes: [], cosmetics: [] }
  const t = ctx.now()
  for (const r of all<{ kind: 'cape' | 'cosmetic', item_id: string }>(ctx.db, 'SELECT kind, item_id FROM partner_items WHERE partner_id = ? ORDER BY created_at, item_id', partnerId)) {
    try {
      if (r.kind === 'cape') {
        const c = getCape(ctx, r.item_id)
        if (!c || c.retired || c.status !== 'approved') continue
        grantCape(ctx, uuid, c.id, 'partner')
        out.capes.push({ capeId: c.id, name: c.name })
      } else {
        const c = getCosmetic(ctx, r.item_id)
        if (!c || c.retired || c.status !== 'approved') continue
        grantCosmetic(ctx, uuid, c.id, 'partner')
        out.cosmetics.push({ id: c.id, name: c.name, slot: cosmeticView(ctx, c).slot })
      }
      run(
        ctx.db,
        'INSERT INTO partner_grants (uuid, kind, item_id, partner_id, granted_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING',
        uuid, r.kind, r.item_id, partnerId, t,
      )
    } catch (err) {
      // Exklusive Teile (z. B. Event-Farben) dürfen die Einlösung nicht verhindern.
      if (!(err instanceof ApiError)) throw err
    }
  }
  return out
}

/** Ein neu zugeordnetes Teil sofort an alle aktuellen Unterstützer geben (Quelle `partner`). Liefert die Zahl. */
function grantToSupporters(ctx: AppContext, partnerId: number, kind: 'cape' | 'cosmetic', itemId: string): number {
  const t = ctx.now()
  let n = 0
  for (const r of all<{ uuid: string }>(ctx.db, 'SELECT uuid FROM partner_support WHERE partner_id = ?', partnerId)) {
    try {
      if (kind === 'cape') grantCape(ctx, r.uuid, itemId, 'partner')
      else grantCosmetic(ctx, r.uuid, itemId, 'partner')
      run(ctx.db, 'INSERT INTO partner_grants (uuid, kind, item_id, partner_id, granted_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING', r.uuid, kind, itemId, partnerId, t)
      n++
    } catch (err) {
      if (!(err instanceof ApiError)) throw err
    }
  }
  return n
}

/**
 * Partner-Code einlösen (§44.2). `null` = kein (aktiver) Partner-Code → der Aufrufer versucht den nächsten Code-Typ.
 * Einmal-Code eines anderen oder gesperrt → 410 `partner_code_used`; der eigene ist idempotent.
 */
export function redeemPartnerCode(ctx: AppContext, uuid: string, rawCode: string): RedeemPartnerResult | null {
  const code = rawCode.trim().toUpperCase()
  if (!PARTNER_FIXED.test(code) && !PARTNER_ONETIME.test(code)) return null
  const k = one<CodeRow & { state: PartnerState }>(
    ctx.db,
    'SELECT k.*, p.state FROM partner_codes k JOIN partners p ON p.id = k.partner_id WHERE k.code = ?',
    code,
  )
  if (!k || k.state !== 'active') return null
  // Deaktivierter fester Code verhält sich wie ein unbekannter.
  if (k.kind === 'fixed' && k.blocked === 1) return null
  const oneTime = k.kind === 'onetime'
  if (oneTime && k.redeemed_by !== uuid && (k.blocked === 1 || k.redeemed_by !== null)) {
    throw new ApiError(410, 'partner_code_used', 'This code has already been used')
  }
  const pid = k.partner_id
  const t = ctx.now()
  const prevPartner = supportRow(ctx, uuid)?.partner_id ?? null
  const r = tx(ctx.db, () => {
    const already = one(ctx.db, 'SELECT 1 AS x FROM partner_redemptions WHERE code_id = ? AND uuid = ?', k.id, uuid) !== undefined
    let firstOneTime = false
    if (!already) {
      if (oneTime) {
        const took = run(ctx.db, 'UPDATE partner_codes SET redeemed_by = ?, redeemed_at = ? WHERE id = ? AND redeemed_by IS NULL AND blocked = 0', uuid, t, k.id)
        if (took === 0) throw new ApiError(410, 'partner_code_used', 'This code has already been used')
        firstOneTime = true
      }
      run(ctx.db, 'INSERT INTO partner_redemptions (partner_id, code_id, uuid, kind, at) VALUES (?, ?, ?, ?, ?)', pid, k.id, uuid, k.kind, t)
      bumpDaily(ctx, pid, oneTime ? 'onetime' : 'fixed')
    }
    let supportChanged = false
    let lockUntil: number | null = null
    const s = supportRow(ctx, uuid)
    if (s?.partner_id !== pid) {
      lockUntil = s?.last_partner_id === pid ? null : switchLockUntil(ctx, s)
      if (lockUntil === null) {
        supportChanged = true
        // Gleicher Partner wie zuletzt begonnen → der ursprüngliche Start bleibt.
        const restart = s?.last_partner_id !== pid
        run(
          ctx.db,
          `INSERT INTO partner_support (uuid, partner_id, last_partner_id, last_started_at, updated_at) VALUES (?, ?, ?, ?, ?)
           ON CONFLICT(uuid) DO UPDATE SET partner_id = excluded.partner_id, last_partner_id = excluded.last_partner_id,
             last_started_at = excluded.last_started_at, updated_at = excluded.updated_at`,
          uuid, pid, pid, restart ? t : s!.last_started_at, t,
        )
        bumpDaily(ctx, pid, 'new_supporters')
        if (prevPartner !== null && prevPartner !== pid) bumpDaily(ctx, prevPartner, 'left_count')
      }
    }
    // Kosmetik gibt es auch ohne Wechsel (z. B. während der 30 Tage).
    const granted = grantItems(ctx, uuid, pid)
    return { supportChanged, lockUntil, granted, firstOneTime }
  })
  const p = partnerById(ctx, pid)!
  if (r.supportChanged) {
    notifySupport(ctx, uuid, p)
    queueWebhook(ctx, pid, 'partner.supported', { supporters: supporterCount(ctx, pid) })
    if (prevPartner !== null && prevPartner !== pid) queueWebhook(ctx, prevPartner, 'partner.left', { supporters: supporterCount(ctx, prevPartner) })
  }
  if (r.firstOneTime) queueWebhook(ctx, pid, 'code.redeemed', { code: k.code, ref: k.ref })
  const until = r.supportChanged ? switchLockUntil(ctx, supportRow(ctx, uuid)) : r.lockUntil ?? switchLockUntil(ctx, supportRow(ctx, uuid))
  return {
    kind: 'partner',
    partner: partnerCard(ctx, p),
    supportChanged: r.supportChanged,
    switchAvailableAt: until === null ? null : iso(until),
    granted: r.granted,
    oneTime,
  }
}

/** Unterstützung beenden (Kosmetik bleibt). */
export function endPartnerSupport(ctx: AppContext, uuid: string): void {
  const prev = supportRow(ctx, uuid)?.partner_id ?? null
  if (prev === null) return
  run(ctx.db, 'UPDATE partner_support SET partner_id = NULL, updated_at = ? WHERE uuid = ?', ctx.now(), uuid)
  bumpDaily(ctx, prev, 'left_count')
  notifySupport(ctx, uuid, null)
  queueWebhook(ctx, prev, 'partner.left', { supporters: supporterCount(ctx, prev) })
}

export interface MyPartnerState {
  support: { partner: PartnerCard | null, startedAt: string | null, switchAvailableAt: string | null }
  owned: { kind: 'cape' | 'cosmetic', id: string, name: string, partner: PartnerRef | null }[]
  member: { slug: string, name: string, rights: PartnerRight[], owner: boolean }[]
  invites: { slug: string, name: string, rights: PartnerRight[], createdAt: string }[]
}

/** `GET /v1/me/partner`. */
export function myPartnerState(ctx: AppContext, uuid: string): MyPartnerState {
  const s = supportRow(ctx, uuid)
  const p = s?.partner_id ? partnerById(ctx, s.partner_id) : undefined
  const lock = switchLockUntil(ctx, s)
  const owned: MyPartnerState['owned'] = []
  for (const g of all<{ kind: 'cape' | 'cosmetic', item_id: string, slug: string | null, pname: string | null }>(
    ctx.db,
    `SELECT g.kind, g.item_id, p.slug, p.name AS pname FROM partner_grants g LEFT JOIN partners p ON p.id = g.partner_id
     WHERE g.uuid = ? AND ((g.kind = 'cape' AND EXISTS (SELECT 1 FROM user_capes c WHERE c.uuid = g.uuid AND c.cape_id = g.item_id))
       OR (g.kind = 'cosmetic' AND EXISTS (SELECT 1 FROM user_cosmetics c WHERE c.uuid = g.uuid AND c.cosmetic_id = g.item_id)))
     ORDER BY g.granted_at, g.item_id`,
    uuid,
  )) {
    const name = g.kind === 'cape' ? getCape(ctx, g.item_id)?.name : getCosmetic(ctx, g.item_id)?.name
    if (name === undefined) continue
    owned.push({ kind: g.kind, id: g.item_id, name, partner: g.slug ? { slug: g.slug, name: g.pname ?? '' } : null })
  }
  const member: MyPartnerState['member'] = all<PartnerRow>(ctx.db, 'SELECT * FROM partners WHERE owner_uuid = ? ORDER BY name COLLATE NOCASE', uuid)
    .map((x) => ({ slug: x.slug, name: x.name, rights: [...PARTNER_RIGHTS], owner: true }))
  for (const m of all<MemberRow & { slug: string, name: string }>(
    ctx.db,
    `SELECT m.*, p.slug, p.name FROM partner_members m JOIN partners p ON p.id = m.partner_id
     WHERE m.uuid = ? AND m.status = 'active' AND (p.owner_uuid IS NULL OR p.owner_uuid <> m.uuid) ORDER BY p.name COLLATE NOCASE`,
    uuid,
  )) member.push({ slug: m.slug, name: m.name, rights: parseRights(m.rights), owner: false })
  const invites = all<MemberRow & { slug: string, name: string }>(
    ctx.db,
    "SELECT m.*, p.slug, p.name FROM partner_members m JOIN partners p ON p.id = m.partner_id WHERE m.uuid = ? AND m.status = 'pending' ORDER BY m.created_at",
    uuid,
  ).map((m) => ({ slug: m.slug, name: m.name, rights: parseRights(m.rights), createdAt: iso(m.created_at) }))
  return {
    support: {
      partner: p && p.state === 'active' ? partnerCard(ctx, p) : null,
      startedAt: p && p.state === 'active' && s?.last_started_at ? iso(s.last_started_at) : null,
      switchAvailableAt: lock === null ? null : iso(lock),
    },
    owned,
    member,
    invites,
  }
}

// ---------------------------------------------------------------- Handelnder im Dashboard

export interface PartnerActor {
  user: string
  partner: PartnerRow
  owner: boolean
  /** Team-Modus (§44.1): `partners.manage`, alle Rechte, jede Änderung protokolliert. */
  team: boolean
  rights: ReadonlySet<PartnerRight>
}

export function isPartnerManager(ctx: AppContext, user: string): boolean {
  const staff = teamOf(ctx, user)
  return !!staff && can(staff, 'partners.manage')
}

/**
 * Wer handelt im Dashboard von `slug`: Inhaber (alle Rechte), Team mit `partners.manage` (Team-Modus) oder aktives
 * Mitglied mit dem Recht `need` (`owner` = nur Inhaber/Team, `null` = irgendein Zugang). Sonst 403 `partner_forbidden`.
 */
export function partnerActor(ctx: AppContext, user: string, slug: string, need: PartnerRight | 'owner' | null): PartnerActor {
  const p = requirePartner(ctx, slug)
  if (p.owner_uuid === user) return { user, partner: p, owner: true, team: false, rights: new Set(PARTNER_RIGHTS) }
  if (isPartnerManager(ctx, user)) return { user, partner: p, owner: false, team: true, rights: new Set(PARTNER_RIGHTS) }
  const m = one<MemberRow>(ctx.db, "SELECT * FROM partner_members WHERE partner_id = ? AND uuid = ? AND status = 'active'", p.id, user)
  if (!m) throw forbidden('partner_forbidden', 'You have no access to this partner')
  const rights = new Set(parseRights(m.rights))
  if (need === 'owner' || (need !== null && !rights.has(need))) throw forbidden('partner_right', 'You do not have this right for this partner')
  return { user, partner: p, owner: false, team: false, rights }
}

const TEAM_ACTION_PREFIX = 'partner.team.'
const auditTarget = (p: Pick<PartnerRow, 'slug'>) => `partner:${p.slug}`

/** Änderung eines Mitglieds oder des Teams protokollieren; im Team-Modus erfahren es Inhaber und Mitglieder. */
function auditActor(ctx: AppContext, a: PartnerActor, action: string, detail = ''): void {
  if (a.team) {
    audit(ctx, a.user, `${TEAM_ACTION_PREFIX}${action}`, auditTarget(a.partner), detail)
    notifyMembers(ctx, a.partner, 'team_changed', { action })
  } else if (!a.owner) {
    audit(ctx, a.user, `partner.member.${action}`, auditTarget(a.partner), detail)
  }
}

function takeWrite(ctx: AppContext, user: string): void {
  const r = ctx.limiter.take(`partner-write:${user}`, RULES.partnerWriteUser)
  if (!r.ok) throw tooMany(r.retryAfter)
}

function takeUpload(ctx: AppContext, partnerId: number): void {
  const r = ctx.limiter.take(`partner-upload:${partnerId}`, RULES.partnerUpload)
  if (!r.ok) throw tooMany(r.retryAfter)
}

function requireActivePartner(p: PartnerRow): void {
  if (p.state !== 'active') throw forbidden('partner_paused', 'This partner is paused')
}

// ---------------------------------------------------------------- Dashboard

export interface BatchView {
  id: number
  label: string
  via: 'dashboard' | 'api'
  count: number
  redeemed: number
  blocked: number
  createdAt: string
  blockedAt: string | null
}

export interface RequestView {
  id: number
  kind: RequestKind
  channel: PromoChannel | null
  status: 'open' | 'accepted' | 'rejected' | 'withdrawn'
  data: Record<string, unknown>
  /** Bild (Logo, Umhang) unter `…/requests/{id}/image.png`. */
  hasImage: boolean
  reason: string | null
  createdAt: string
  decidedAt: string | null
}

export interface PartnerStats {
  supporters: number
  newSupporters7d: number
  redemptions: { total: number, fixed: number, onetime: number }
  /** 30 Tage bis heute (ältester zuerst). */
  daily: PartnerStatDay[]
  /** Summen der letzten 30 Tage und der 30 Tage davor (Vergleich). */
  current30d: PartnerPeriod
  previous30d: PartnerPeriod
  newPlayers: number
  capeWorn: number
  cosmeticWorn: number
  left30d: number
  serverJoins7d: number
  serverPlayers7d: number
  onlineToday: number
  playedToday: number
  clicks30d: number
  downloads30d: number
}

export interface PartnerMemberView {
  uuid: string
  name: string
  rights: PartnerRight[]
  status: 'pending' | 'active'
  createdAt: string
}

/** Partner-Teil im Dashboard mit Daten für die 3D-Vorschau. */
export interface PartnerItemView extends PartnerCosmeticRef {
  /** Umhang: Textur (Frame-Streifen) für die 3D-Figur, sonst `null`. */
  cape: { url: string, scale: number, frames: number, frameTimeMs: number | null } | null
  /** Kosmetik im Format v2: wie ein Eintrag von `GET /v1/site/cosmetics`, sonst `null`. */
  hat: PublicHat | null
}

export interface PartnerDashboard {
  slug: string
  name: string
  category: PartnerCategory
  state: PartnerState
  prefix: string
  logo: string | null
  wikiUrl: string | null
  /** Team-Schalter: darf der Partner einen Partner-Server einreichen/zeigen? */
  serverAllowed: boolean
  owner: { uuid: string, name: string } | null
  profile: { text: { de: string, en: string }, links: PartnerLink[] }
  code: string | null
  /** Alle festen Codes mit Einlösungen (Recht `codes`). */
  fixedCodes: FixedCodeView[] | null
  quota: { total: number, used: number, free: number, requested: number | null }
  batches: BatchView[] | null
  stats: PartnerStats | null
  server: {
    current: (ServerSettings & { hidden: boolean, online: boolean, latencyMs: number | null, players: { online: number, max: number } | null, lastOnlineAt: string | null, lastCheckedAt: string | null, reason: string | null }) | null
    joins7d: number
    players7d: number
  } | null
  links: { path: string, campaigns: { campaign: string, clicks30d: number, downloads30d: number }[] } | null
  apiKey: { hint: string, createdAt: string, lastUsedAt: string | null } | null
  webhook: { config: WebhookView | null, deliveries: DeliveryView[] } | null
  cosmetics: PartnerItemView[]
  members: PartnerMemberView[] | null
  requests: RequestView[]
  viewer: { owner: boolean, team: boolean, rights: PartnerRight[] }
  teamChanges: { action: string, at: string }[]
}

interface ServerSettings {
  id: string
  name: string
  address: string
  minVersion: string
  maxVersion: string
  motd: string
}

function batchView(ctx: AppContext, b: BatchRow): BatchView {
  const r = one<{ redeemed: number, blocked: number }>(
    ctx.db,
    'SELECT COALESCE(SUM(redeemed_by IS NOT NULL), 0) AS redeemed, COALESCE(SUM(blocked = 1 AND redeemed_by IS NULL), 0) AS blocked FROM partner_codes WHERE batch_id = ?',
    b.id,
  )!
  return { id: b.id, label: b.label, via: b.via, count: b.count, redeemed: r.redeemed, blocked: r.blocked, createdAt: iso(b.created_at), blockedAt: isoOrNull(b.blocked_at) }
}

function requestData(r: RequestRow): Record<string, unknown> {
  const d = parseJson<Record<string, unknown>>(r.data, {})
  // Interne Felder (Dateiname) nicht zeigen.
  const { sha: _sha, ...rest } = d
  return rest
}

function requestView(r: RequestRow): RequestView {
  return {
    id: r.id,
    kind: r.kind,
    channel: r.channel,
    status: r.status,
    data: requestData(r),
    hasImage: (r.kind === 'logo' || r.kind === 'cape') && r.status === 'open',
    reason: r.reason,
    createdAt: iso(r.created_at),
    decidedAt: isoOrNull(r.decided_at),
  }
}

function openQuotaRequest(ctx: AppContext, partnerId: number): number | null {
  const r = one<RequestRow>(ctx.db, "SELECT * FROM partner_requests WHERE partner_id = ? AND kind = 'quota' AND status = 'open'", partnerId)
  return r ? (parseJson<{ amount?: number }>(r.data, {}).amount ?? null) : null
}

export function partnerStats(ctx: AppContext, p: PartnerRow): PartnerStats {
  const n = (sql: string, ...params: (string | number)[]) => one<{ n: number }>(ctx.db, sql, ...params)!.n
  const t = ctx.now()
  const trend = partnerTrend(ctx, p.id)
  const daily = trend.daily
  const totals = one<{ fixed: number, onetime: number }>(
    ctx.db,
    "SELECT COALESCE(SUM(kind = 'fixed'), 0) AS fixed, COALESCE(SUM(kind = 'onetime'), 0) AS onetime FROM partner_redemptions WHERE partner_id = ?",
    p.id,
  )!
  const dayNo = Math.floor(t / DAY)
  const activity = one<{ online: number, played: number }>(
    ctx.db,
    `SELECT COUNT(*) AS online, COALESCE(SUM(COALESCE(a.last_played_day, -1) = ?), 0) AS played
     FROM partner_support s JOIN achievement_playtime a ON a.uuid = s.uuid WHERE s.partner_id = ? AND a.last_day = ?`,
    dayNo, p.id, dayNo,
  )!
  const s = liveServer(ctx, p.id)
  const joins = s ? serverJoinStats(ctx, s.id, 7) : { joins: 0, distinct: 0 }
  return {
    supporters: supporterCount(ctx, p.id),
    newSupporters7d: daily.slice(-7).reduce((a, d) => a + d.newSupporters, 0),
    redemptions: { total: totals.fixed + totals.onetime, fixed: totals.fixed, onetime: totals.onetime },
    daily,
    current30d: trend.current30d,
    previous30d: trend.previous30d,
    newPlayers: n(
      `SELECT COUNT(*) AS n FROM (SELECT uuid, MIN(at) AS first FROM partner_redemptions WHERE partner_id = ? GROUP BY uuid) f
       JOIN users u ON u.uuid = f.uuid WHERE f.first - u.created_at <= ?`,
      p.id, PARTNER_NEW_PLAYER_MS,
    ),
    capeWorn: n(
      `SELECT COUNT(*) AS n FROM partner_support s JOIN users u ON u.uuid = s.uuid
       WHERE s.partner_id = ? AND u.active_cape_id IN (SELECT item_id FROM partner_items WHERE partner_id = ? AND kind = 'cape')`,
      p.id, p.id,
    ),
    cosmeticWorn: n(
      `SELECT COUNT(*) AS n FROM partner_support s WHERE s.partner_id = ? AND EXISTS (
         SELECT 1 FROM equipped_cosmetics e WHERE e.uuid = s.uuid
           AND e.cosmetic_id IN (SELECT item_id FROM partner_items WHERE partner_id = ? AND kind = 'cosmetic'))`,
      p.id, p.id,
    ),
    left30d: trend.current30d.left,
    serverJoins7d: joins.joins,
    serverPlayers7d: joins.distinct,
    onlineToday: activity.online,
    playedToday: activity.played,
    clicks30d: trend.current30d.clicks,
    downloads30d: trend.current30d.downloads,
  }
}

function membersOf(ctx: AppContext, partnerId: number): PartnerMemberView[] {
  return all<MemberRow & { name: string | null }>(
    ctx.db,
    'SELECT m.*, u.name FROM partner_members m LEFT JOIN users u ON u.uuid = m.uuid WHERE m.partner_id = ? ORDER BY m.created_at',
    partnerId,
  ).map((m) => ({ uuid: m.uuid, name: m.name ?? '', rights: parseRights(m.rights), status: m.status, createdAt: iso(m.created_at) }))
}

function campaignStats(ctx: AppContext, p: PartnerRow): { campaign: string, clicks30d: number, downloads30d: number }[] {
  const from = dayOf(ctx.now() - 29 * DAY)
  const sums = new Map(all<{ campaign: string, clicks: number, downloads: number }>(
    ctx.db,
    'SELECT campaign, SUM(clicks) AS clicks, SUM(downloads) AS downloads FROM partner_link_stats WHERE partner_id = ? AND day >= ? GROUP BY campaign',
    p.id, from,
  ).map((r) => [r.campaign, r]))
  const list = ['', ...all<{ campaign: string }>(ctx.db, 'SELECT campaign FROM partner_links WHERE partner_id = ? ORDER BY created_at, campaign', p.id).map((r) => r.campaign)]
  return list.map((c) => ({ campaign: c, clicks30d: sums.get(c)?.clicks ?? 0, downloads30d: sums.get(c)?.downloads ?? 0 }))
}

function teamChangesOf(ctx: AppContext, p: PartnerRow): { action: string, at: string }[] {
  return all<{ action: string, at: number }>(
    ctx.db,
    "SELECT action, at FROM admin_log WHERE target = ? AND at >= ? AND action LIKE 'partner.team.%' ORDER BY at DESC, id DESC LIMIT 20",
    auditTarget(p), ctx.now() - 90 * DAY,
  ).map((r) => ({ action: r.action.slice(TEAM_ACTION_PREFIX.length), at: iso(r.at) }))
}

function serverSettings(s: PartnerServerRow): ServerSettings {
  return { id: s.id, name: s.name, address: s.address, minVersion: s.min_version, maxVersion: s.max_version, motd: s.motd }
}

function itemView(ctx: AppContext, i: PartnerCosmeticRef): PartnerItemView {
  if (i.kind === 'cape') {
    const c = getCape(ctx, i.id)
    return { ...i, cape: c ? { url: capeUrl(ctx, c), scale: Math.round(c.width / 64), frames: c.frames, frameTimeMs: c.frames > 1 ? c.frame_time_ms : null } : null, hat: null }
  }
  return { ...i, cape: null, hat: hatPreview(ctx, i.id) }
}

export function partnerDashboard(ctx: AppContext, user: string, slug: string): PartnerDashboard {
  const a = partnerActor(ctx, user, slug, null)
  const p = a.partner
  const may = (r: PartnerRight) => a.rights.has(r)
  const s = liveServer(ctx, p.id)
  const key = one<{ hint: string, created_at: number, last_used_at: number | null }>(ctx.db, 'SELECT hint, created_at, last_used_at FROM partner_api_keys WHERE partner_id = ?', p.id)
  const joins = s ? serverJoinStats(ctx, s.id, 7) : { joins: 0, distinct: 0 }
  return {
    slug: p.slug,
    name: p.name,
    category: p.category,
    state: p.state,
    prefix: p.prefix,
    logo: logoPath(p.slug, p.logo_sha),
    wikiUrl: p.wiki_url,
    serverAllowed: p.server_allowed === 1,
    owner: p.owner_uuid ? { uuid: p.owner_uuid, name: nameOf(ctx, p.owner_uuid) } : null,
    profile: { text: { de: p.text_de, en: p.text_en }, links: parseLinks(p.links) },
    code: fixedCode(ctx, p.id),
    fixedCodes: may('codes') ? fixedCodeViews(ctx, p.id) : null,
    quota: { total: p.quota_total, used: p.quota_used, free: Math.max(0, p.quota_total - p.quota_used), requested: openQuotaRequest(ctx, p.id) },
    batches: may('codes') ? all<BatchRow>(ctx.db, 'SELECT * FROM partner_batches WHERE partner_id = ? ORDER BY created_at DESC, id DESC LIMIT 50', p.id).map((b) => batchView(ctx, b)) : null,
    stats: may('stats') ? partnerStats(ctx, p) : null,
    server: may('server')
      ? {
          current: s
            ? {
                ...serverSettings(s),
                hidden: s.hidden === 1,
                online: s.online === 1,
                latencyMs: s.latency_ms,
                players: s.players_online === null || s.players_max === null ? null : { online: s.players_online, max: s.players_max },
                lastOnlineAt: isoOrNull(s.last_online_at),
                lastCheckedAt: isoOrNull(s.last_checked_at),
                reason: s.status_reason,
              }
            : null,
          joins7d: joins.joins,
          players7d: joins.distinct,
        }
      : null,
    links: may('links') ? { path: `/go/${p.slug}`, campaigns: campaignStats(ctx, p) } : null,
    apiKey: may('api') && key ? { hint: key.hint, createdAt: iso(key.created_at), lastUsedAt: isoOrNull(key.last_used_at) } : null,
    webhook: may('webhook') ? { config: webhookView(ctx, p.id), deliveries: deliveriesOf(ctx, p.id) } : null,
    cosmetics: itemsOf(ctx, p.id).map((i) => itemView(ctx, i)),
    members: a.owner || a.team ? membersOf(ctx, p.id) : null,
    requests: all<RequestRow>(ctx.db, 'SELECT * FROM partner_requests WHERE partner_id = ? ORDER BY created_at DESC, id DESC LIMIT 40', p.id)
      .filter((r) => may(REQUEST_RIGHT[r.kind]))
      .map(requestView),
    viewer: { owner: a.owner, team: a.team, rights: PARTNER_RIGHTS.filter((r) => a.rights.has(r)) },
    teamChanges: teamChangesOf(ctx, p),
  }
}

// ---------------------------------------------------------------- Einmal-Codes

function newOneTimeCode(prefix: string): string {
  let s = ''
  const bytes = randomBytes(8)
  for (let i = 0; i < 8; i++) s += ONETIME_ALPHABET[bytes[i]! & 31]
  return `${prefix}-${s.slice(0, 4)}-${s.slice(4)}`
}

/** Freie Codes erzeugen (innerhalb einer Transaktion des Aufrufers). Kontingent muss vorher geprüft sein. */
function insertCodes(ctx: AppContext, p: PartnerRow, batchId: number, count: number, ref: string | null): string[] {
  const out: string[] = []
  const t = ctx.now()
  while (out.length < count) {
    const code = newOneTimeCode(p.prefix)
    const ok = run(
      ctx.db,
      "INSERT INTO partner_codes (partner_id, code, kind, batch_id, ref, created_at) VALUES (?, ?, 'onetime', ?, ?, ?) ON CONFLICT(code) DO NOTHING",
      p.id, code, batchId, ref, t,
    )
    if (ok > 0) out.push(code)
  }
  return out
}

/** Kontingent verbrauchen; bei < 10 % frei einmal `quota.low`. */
function useQuota(ctx: AppContext, p: PartnerRow, count: number): void {
  const n = run(ctx.db, 'UPDATE partners SET quota_used = quota_used + ?, updated_at = ? WHERE id = ? AND quota_used + ? <= quota_total', count, ctx.now(), p.id, count)
  if (n === 0) throw forbidden('partner_quota', 'Not enough one-time code quota left')
}

function checkQuotaLow(ctx: AppContext, partnerId: number): void {
  const p = partnerById(ctx, partnerId)!
  const free = p.quota_total - p.quota_used
  if (p.quota_total > 0 && free * 10 < p.quota_total && p.quota_low_sent === 0) {
    run(ctx.db, 'UPDATE partners SET quota_low_sent = 1 WHERE id = ?', p.id)
    queueWebhook(ctx, p.id, 'quota.low', { total: p.quota_total, used: p.quota_used })
  }
}

export interface CreatedBatch {
  batch: BatchView
  codes: string[]
}

/** Paket im Dashboard erzeugen (Recht `codes`). Codes stehen NUR in dieser Antwort (und im CSV). */
export function createBatch(ctx: AppContext, user: string, slug: string, raw: unknown): CreatedBatch {
  const a = partnerActor(ctx, user, slug, 'codes')
  takeWrite(ctx, user)
  requireActivePartner(a.partner)
  const input = parse(batchBody, raw)
  const t = ctx.now()
  const r = tx(ctx.db, () => {
    useQuota(ctx, a.partner, input.count)
    const id = one<{ id: number }>(
      ctx.db,
      "INSERT INTO partner_batches (partner_id, label, via, count, created_at, created_by) VALUES (?, ?, 'dashboard', ?, ?, ?) RETURNING id",
      a.partner.id, input.label, input.count, t, user,
    )!.id
    return { id, codes: insertCodes(ctx, a.partner, id, input.count, null) }
  })
  checkQuotaLow(ctx, a.partner.id)
  auditActor(ctx, a, 'batch.create', `${input.label} (${input.count})`)
  return { batch: batchView(ctx, one<BatchRow>(ctx.db, 'SELECT * FROM partner_batches WHERE id = ?', r.id)!), codes: r.codes }
}

/** Offene (nicht eingelöste) Codes eines Pakets sperren. Gehen nicht ans Kontingent zurück. */
export function blockBatch(ctx: AppContext, user: string, slug: string, batchId: number): { batch: BatchView, blocked: number } {
  const a = partnerActor(ctx, user, slug, 'codes')
  takeWrite(ctx, user)
  const b = one<BatchRow>(ctx.db, 'SELECT * FROM partner_batches WHERE id = ? AND partner_id = ?', batchId, a.partner.id)
  if (!b) throw notFound('batch_not_found', 'Batch not found')
  const n = tx(ctx.db, () => {
    const k = run(ctx.db, 'UPDATE partner_codes SET blocked = 1 WHERE batch_id = ? AND redeemed_by IS NULL AND blocked = 0', b.id)
    run(ctx.db, 'UPDATE partner_batches SET blocked_at = COALESCE(blocked_at, ?) WHERE id = ?', ctx.now(), b.id)
    return k
  })
  auditActor(ctx, a, 'batch.block', `${b.label} (${n})`)
  return { batch: batchView(ctx, one<BatchRow>(ctx.db, 'SELECT * FROM partner_batches WHERE id = ?', b.id)!), blocked: n }
}

const csvCell = (s: string) => (/[",\n\r]/.test(s) || /^[=+\-@]/.test(s) ? `"${s.replace(/^([=+\-@])/, "'$1").replace(/"/g, '""')}"` : s)

/** CSV eines Pakets (Code, ref, Status, eingelöst am) – nie Spielerdaten. */
export function batchCsv(ctx: AppContext, user: string, slug: string, batchId: number): { filename: string, csv: string } {
  const a = partnerActor(ctx, user, slug, 'codes')
  const b = one<BatchRow>(ctx.db, 'SELECT * FROM partner_batches WHERE id = ? AND partner_id = ?', batchId, a.partner.id)
  if (!b) throw notFound('batch_not_found', 'Batch not found')
  const rows = all<CodeRow>(ctx.db, 'SELECT * FROM partner_codes WHERE batch_id = ? ORDER BY id', b.id)
  const lines = ['code,ref,status,redeemed_at']
  for (const r of rows) {
    const status = r.redeemed_by !== null || r.redeemed_at !== null ? 'redeemed' : r.blocked ? 'blocked' : 'open'
    lines.push([r.code, r.ref ?? '', status, r.redeemed_at === null ? '' : iso(r.redeemed_at)].map(csvCell).join(','))
  }
  auditActor(ctx, a, 'batch.csv', b.label)
  return { filename: `${a.partner.slug}-${b.id}.csv`, csv: `${lines.join('\r\n')}\r\n` }
}

// ---------------------------------------------------------------- Partner-API (§44.5)

function newApiKey(): string {
  let s = ''
  while (s.length < 32) {
    for (const b of randomBytes(48)) {
      if (b < 248 && s.length < 32) s += BASE62[b % 62]
    }
  }
  return `trsp_live_${s}`
}

/** Schlüssel erzeugen/ersetzen (Recht `api`). Wird nur EINMAL zurückgegeben, gespeichert als SHA-256. */
export function rotateApiKey(ctx: AppContext, user: string, slug: string): { key: string, hint: string, createdAt: string } {
  const a = partnerActor(ctx, user, slug, 'api')
  takeWrite(ctx, user)
  const key = newApiKey()
  const t = ctx.now()
  const hint = key.slice(-4)
  run(
    ctx.db,
    `INSERT INTO partner_api_keys (partner_id, key_hash, hint, created_at, created_by) VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(partner_id) DO UPDATE SET key_hash = excluded.key_hash, hint = excluded.hint, created_at = excluded.created_at,
       created_by = excluded.created_by, last_used_at = NULL`,
    a.partner.id, sha256Hex(key), hint, t, user,
  )
  auditActor(ctx, a, 'api_key.rotate', hint)
  return { key, hint, createdAt: iso(t) }
}

export function revokeApiKey(ctx: AppContext, user: string, slug: string): void {
  const a = partnerActor(ctx, user, slug, 'api')
  takeWrite(ctx, user)
  run(ctx.db, 'DELETE FROM partner_api_keys WHERE partner_id = ?', a.partner.id)
  auditActor(ctx, a, 'api_key.revoke')
}

/** `Authorization: Bearer trsp_live_…` → Partner. Falsch → 401, pausiert → 403 `partner_paused`. 60/min je Schlüssel. */
export function authenticatePartnerKey(ctx: AppContext, authorization: string | undefined, ip: string): PartnerRow {
  const fail = () => {
    const r = ctx.limiter.take(`partner-api-fail:${ip}`, RULES.partnerApiFailIp)
    if (!r.ok) return tooMany(r.retryAfter)
    return unauthorized('Invalid partner API key')
  }
  const m = /^Bearer (\S+)$/.exec(authorization ?? '')
  if (!m || !API_KEY.test(m[1]!)) throw fail()
  const hash = sha256Hex(m[1]!)
  const k = one<{ partner_id: number }>(ctx.db, 'SELECT partner_id FROM partner_api_keys WHERE key_hash = ?', hash)
  if (!k) throw fail()
  const r = ctx.limiter.take(`partner-api:${hash.slice(0, 16)}`, RULES.partnerApiKey)
  if (!r.ok) throw tooMany(r.retryAfter)
  const p = partnerById(ctx, k.partner_id)!
  if (p.state !== 'active') throw forbidden('partner_paused', 'This partner is paused')
  run(ctx.db, 'UPDATE partner_api_keys SET last_used_at = ? WHERE partner_id = ?', ctx.now(), p.id)
  return p
}

/** `POST /v1/partner-api/codes`: mit `ref` (nur `count: 1`) idempotent – derselbe ref liefert denselben Code. */
export function apiCreateCodes(ctx: AppContext, p: PartnerRow, raw: unknown): { codes: string[], quota: { total: number, used: number } } {
  const input = parse(apiCodesBody, raw)
  if (input.ref !== undefined && input.count !== 1) throw badRequest('invalid_request', 'With ref, count must be 1')
  const quota = () => {
    const x = partnerById(ctx, p.id)!
    return { total: x.quota_total, used: x.quota_used }
  }
  if (input.ref !== undefined) {
    const old = one<{ code: string }>(ctx.db, 'SELECT code FROM partner_codes WHERE partner_id = ? AND ref = ?', p.id, input.ref)
    if (old) return { codes: [old.code], quota: quota() }
  }
  const label = input.label ?? 'API'
  const t = ctx.now()
  const codes = tx(ctx.db, () => {
    useQuota(ctx, p, input.count)
    // Gleiche Bezeichnung → gleiches Paket (z. B. „Neukunden November“), solange es nicht gesperrt ist.
    let batch = one<{ id: number }>(ctx.db, "SELECT id FROM partner_batches WHERE partner_id = ? AND via = 'api' AND label = ? AND blocked_at IS NULL ORDER BY id DESC LIMIT 1", p.id, label)?.id
    if (batch === undefined) {
      batch = one<{ id: number }>(ctx.db, "INSERT INTO partner_batches (partner_id, label, via, count, created_at) VALUES (?, ?, 'api', 0, ?) RETURNING id", p.id, label, t)!.id
    }
    run(ctx.db, 'UPDATE partner_batches SET count = count + ? WHERE id = ?', input.count, batch)
    return insertCodes(ctx, p, batch, input.count, input.ref ?? null)
  })
  checkQuotaLow(ctx, p.id)
  return { codes, quota: quota() }
}

export function apiCodeStatus(ctx: AppContext, p: PartnerRow, rawCode: string): { code: string, redeemed: boolean, redeemedAt: string | null, blocked: boolean, ref: string | null } {
  const code = rawCode.trim().toUpperCase()
  const k = PARTNER_ONETIME.test(code)
    ? one<CodeRow>(ctx.db, "SELECT * FROM partner_codes WHERE code = ? AND partner_id = ? AND kind = 'onetime'", code, p.id)
    : undefined
  if (!k) throw notFound('code_not_found', 'Code not found')
  return { code: k.code, redeemed: k.redeemed_at !== null, redeemedAt: isoOrNull(k.redeemed_at), blocked: k.blocked === 1, ref: k.ref }
}

export function apiQuota(ctx: AppContext, p: PartnerRow): { total: number, used: number, requested: boolean } {
  const x = partnerById(ctx, p.id)!
  return { total: x.quota_total, used: x.quota_used, requested: openQuotaRequest(ctx, p.id) !== null }
}

// ---------------------------------------------------------------- Anträge

function openRequest(ctx: AppContext, partnerId: number, kind: RequestKind, channel: PromoChannel | null): RequestRow | undefined {
  return channel === null
    ? one<RequestRow>(ctx.db, "SELECT * FROM partner_requests WHERE partner_id = ? AND kind = ? AND status = 'open'", partnerId, kind)
    : one<RequestRow>(ctx.db, "SELECT * FROM partner_requests WHERE partner_id = ? AND kind = ? AND channel = ? AND status = 'open'", partnerId, kind, channel)
}

function insertRequest(ctx: AppContext, p: PartnerRow, kind: RequestKind, channel: PromoChannel | null, data: Record<string, unknown>, user: string): RequestRow {
  return one<RequestRow>(
    ctx.db,
    "INSERT INTO partner_requests (partner_id, kind, channel, status, data, created_at, created_by) VALUES (?, ?, ?, 'open', ?, ?, ?) RETURNING *",
    p.id, kind, channel, JSON.stringify(data), ctx.now(), user,
  )!
}

function checkFixedCodeFree(ctx: AppContext, code: string, partnerId: number | null): void {
  if (isCreatorCodeTaken(ctx, code)) throw conflict('code_taken', 'This code is already taken')
  const own = one<{ partner_id: number }>(ctx.db, "SELECT partner_id FROM partner_codes WHERE code = ? AND kind = 'fixed'", code)
  if (own && own.partner_id !== partnerId) throw conflict('code_taken', 'This code is already taken')
}

/** Antrag stellen (JSON-Arten). Je Art ein offener Antrag, bei `promo` einer je Kanal. */
export function createRequest(ctx: AppContext, user: string, slug: string, raw: unknown): { request: RequestView } {
  const kind = typeof raw === 'object' && raw !== null ? (raw as { kind?: unknown }).kind : undefined
  const need = typeof kind === 'string' && kind in REQUEST_RIGHT ? REQUEST_RIGHT[kind as RequestKind] : null
  const a = partnerActor(ctx, user, slug, need)
  takeWrite(ctx, user)
  const input = parse(requestBody, raw)
  if (input.kind === 'server' && a.partner.server_allowed !== 1) throw serverNotAllowed()
  if (input.kind === 'cape') return selectPartnerCape(ctx, a, input.capeId, input.name)
  const channel = input.kind === 'promo' ? input.channel : null
  if (openRequest(ctx, a.partner.id, input.kind, channel)) throw conflict('partner_request_open', 'There is already an open request of this kind')
  if (input.kind === 'code_change') {
    checkFixedCodeFree(ctx, input.code, a.partner.id)
    if (input.action === 'add') checkFixedCodeRoom(ctx, a.partner.id, input.code)
  }
  if (input.kind === 'server' && compareVer(input.minVersion, input.maxVersion) > 0) throw badRequest('invalid_request', 'minVersion must not be above maxVersion')
  const { kind: k, ...data } = input
  const r = insertRequest(ctx, a.partner, k, channel, data, user)
  auditActor(ctx, a, `request.${k}`)
  return { request: requestView(r) }
}

const serverNotAllowed = () => forbidden('partner_server_not_allowed', 'Partner servers are not enabled for this partner')

/** Inhaber + aktive Mitglieder (deren eigene Uploads als Partner-Umhang wählbar sind). */
function memberUuids(ctx: AppContext, p: PartnerRow): string[] {
  const out = new Set<string>()
  if (p.owner_uuid) out.add(p.owner_uuid)
  for (const m of all<{ uuid: string }>(ctx.db, "SELECT uuid FROM partner_members WHERE partner_id = ? AND status = 'active'", p.id)) out.add(m.uuid)
  return [...out]
}

export interface SelectableCape {
  id: string
  name: string
  owner: { uuid: string, name: string }
  url: string
  width: number
  height: number
  scale: number
  frames: number
  frameTimeMs: number | null
  createdAt: string
}

/** Freigegebene eigene Umhänge (§5.6) von Inhaber und Mitgliedern, die als Partner-Umhang wählbar sind (Recht `cosmetics`). */
export function selectableCapes(ctx: AppContext, user: string, slug: string): { capes: SelectableCape[] } {
  const a = partnerActor(ctx, user, slug, 'cosmetics')
  const owners = memberUuids(ctx, a.partner)
  if (!owners.length) return { capes: [] }
  const rows = all<CapeRow & { owner_name: string | null }>(
    ctx.db,
    `SELECT c.*, u.name AS owner_name FROM capes c LEFT JOIN users u ON u.uuid = c.owner_uuid
     WHERE c.kind = 'upload' AND c.status = 'approved' AND c.retired = 0 AND c.creator_uuid IS NULL AND c.partner_id IS NULL
       AND c.owner_uuid IN (${owners.map(() => '?').join(', ')})
     ORDER BY c.created_at DESC, c.id LIMIT 100`,
    ...owners,
  )
  return {
    capes: rows.map((c) => {
      const v = capeView(ctx, c)
      return {
        id: c.id,
        name: c.name,
        owner: { uuid: c.owner_uuid!, name: c.owner_name ?? '' },
        url: v.url,
        width: v.width,
        height: v.height,
        scale: v.scale,
        frames: v.frames,
        frameTimeMs: v.frameTimeMs,
        createdAt: iso(c.created_at),
      }
    }),
  }
}

/** Gewählten Umhang als Antrag `cape` einreichen; ein offener Umhang-Antrag wird ersetzt. */
function selectPartnerCape(ctx: AppContext, a: PartnerActor, capeId: string, name: string | undefined): { request: RequestView } {
  const c = getCape(ctx, capeId)
  const owners = memberUuids(ctx, a.partner)
  if (!c || c.kind !== 'upload' || c.status !== 'approved' || c.retired || c.creator_uuid || c.partner_id || !c.owner_uuid || !owners.includes(c.owner_uuid)) {
    throw notFound('cape_not_found', 'This cape is not one of the approved uploads of the dashboard members')
  }
  const data = {
    source: 'select',
    capeId: c.id,
    name: (name ?? c.name).slice(0, 32),
    owner: nameOf(ctx, c.owner_uuid),
    width: c.width,
    height: c.height,
    frames: c.frames,
    frameTimeMs: c.frames > 1 ? c.frame_time_ms : null,
  }
  const r = tx(ctx.db, () => {
    const old = openRequest(ctx, a.partner.id, 'cape', null)
    if (old) run(ctx.db, "UPDATE partner_requests SET status = 'withdrawn', decided_at = ? WHERE id = ?", ctx.now(), old.id)
    return { row: insertRequest(ctx, a.partner, 'cape', null, data, a.user), old }
  })
  if (r.old) dropFileIfUnused(ctx, parseJson<{ sha?: string }>(r.old.data, {}).sha)
  auditActor(ctx, a, 'request.cape')
  return { request: requestView(r.row) }
}

function compareVer(a: string, b: string): number {
  const pa = a.split('.').map(Number)
  const pb = b.split('.').map(Number)
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0)
    if (d !== 0) return d
  }
  return 0
}

/** Offenen Antrag zurückziehen (Recht der Art). */
export function withdrawRequest(ctx: AppContext, user: string, slug: string, id: number): void {
  const p = requirePartner(ctx, slug)
  const r = one<RequestRow>(ctx.db, "SELECT * FROM partner_requests WHERE id = ? AND partner_id = ? AND status = 'open'", id, p.id)
  if (!r) throw notFound('request_not_found', 'Request not found')
  const a = partnerActor(ctx, user, slug, REQUEST_RIGHT[r.kind])
  takeWrite(ctx, user)
  run(ctx.db, "UPDATE partner_requests SET status = 'withdrawn', decided_at = ? WHERE id = ?", ctx.now(), id)
  dropFileIfUnused(ctx, parseJson<{ sha?: string }>(r.data, {}).sha)
  auditActor(ctx, a, `request.withdraw.${r.kind}`)
}

/** Logo hochladen (Recht `profile`) → Antrag `logo`; ein offener wird ersetzt. */
export function uploadLogo(ctx: AppContext, user: string, slug: string, body: Buffer): { request: RequestView } {
  const a = partnerActor(ctx, user, slug, 'profile')
  takeWrite(ctx, user)
  takeUpload(ctx, a.partner.id)
  const png = sanitizeLogo(body)
  const sha = storeFile(ctx, png)
  const r = tx(ctx.db, () => {
    const old = openRequest(ctx, a.partner.id, 'logo', null)
    if (old) run(ctx.db, "UPDATE partner_requests SET status = 'withdrawn', decided_at = ? WHERE id = ?", ctx.now(), old.id)
    return { row: insertRequest(ctx, a.partner, 'logo', null, { sha }, user), old }
  })
  if (r.old) dropFileIfUnused(ctx, parseJson<{ sha?: string }>(r.old.data, {}).sha)
  auditActor(ctx, a, 'request.logo')
  return { request: requestView(r.row) }
}

/** Partner-Umhang hochladen (Recht `cosmetics`, HD/animiert erlaubt) → Antrag `cape`; ein offener wird ersetzt. */
export function uploadPartnerCape(ctx: AppContext, user: string, slug: string, body: Buffer, rawQuery: unknown): { request: RequestView } {
  const a = partnerActor(ctx, user, slug, 'cosmetics')
  takeWrite(ctx, user)
  const q = parse(capeQuery, rawQuery)
  takeUpload(ctx, a.partner.id)
  const clean = sanitizeCapeUpload(body, { frames: q.frames })
  if (clean.frames > 1 && q.frameTimeMs === undefined) throw badRequest('frame_time_required', 'Animated capes need the frameTimeMs query parameter')
  const sha = storeFile(ctx, clean.png)
  const data = {
    sha,
    name: q.name ?? `${a.partner.name}`.slice(0, 32),
    width: clean.width,
    height: clean.height,
    frames: clean.frames,
    frameTimeMs: clean.frames > 1 ? q.frameTimeMs! : null,
  }
  const r = tx(ctx.db, () => {
    const old = openRequest(ctx, a.partner.id, 'cape', null)
    if (old) run(ctx.db, "UPDATE partner_requests SET status = 'withdrawn', decided_at = ? WHERE id = ?", ctx.now(), old.id)
    return { row: insertRequest(ctx, a.partner, 'cape', null, data, user), old }
  })
  if (r.old) dropFileIfUnused(ctx, parseJson<{ sha?: string }>(r.old.data, {}).sha)
  auditActor(ctx, a, 'request.cape')
  return { request: requestView(r.row) }
}

function requestImage(ctx: AppContext, r: RequestRow): { png: Buffer, sha256: string, public: boolean } {
  const d = parseJson<{ sha?: string, capeId?: string }>(r.data, {})
  if (r.status === 'open' && r.kind === 'cape' && typeof d.capeId === 'string') {
    // Gewählter eigener Umhang: Bild aus capes/.
    const c = getCape(ctx, d.capeId)
    const file = c ? join(ctx.capeDir, `${c.id}.png`) : ''
    if (!c || !existsSync(file)) throw notFound('image_not_found', 'Image not found')
    return { png: readFileSync(file), sha256: c.sha256, public: false }
  }
  const sha = d.sha
  if (r.status !== 'open' || !sha || !/^[0-9a-f]{64}$/.test(sha)) throw notFound('image_not_found', 'Image not found')
  const file = join(partnerFileDir(ctx), `${sha}.png`)
  if (!existsSync(file)) throw notFound('image_not_found', 'Image not found')
  return { png: readFileSync(file), sha256: sha, public: false }
}

/** Bild eines offenen Logo-/Umhang-Antrags (Mitglied mit dem Recht der Art oder Team). */
export function memberRequestImage(ctx: AppContext, user: string, slug: string, id: number): { png: Buffer, sha256: string, public: boolean } {
  const p = requirePartner(ctx, slug)
  const r = one<RequestRow>(ctx.db, 'SELECT * FROM partner_requests WHERE id = ? AND partner_id = ?', id, p.id)
  if (!r) throw notFound('request_not_found', 'Request not found')
  partnerActor(ctx, user, slug, REQUEST_RIGHT[r.kind])
  return requestImage(ctx, r)
}

// ---------------------------------------------------------------- Werbe-Links (§44.3)

export function addCampaign(ctx: AppContext, user: string, slug: string, raw: unknown): { campaigns: { campaign: string, clicks30d: number, downloads30d: number }[] } {
  const a = partnerActor(ctx, user, slug, 'links')
  takeWrite(ctx, user)
  const input = parse(campaignBody, raw)
  if (one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM partner_links WHERE partner_id = ?', a.partner.id)!.n >= CAMPAIGNS_MAX) {
    throw forbidden('partner_campaign_limit', `At most ${CAMPAIGNS_MAX} campaigns`)
  }
  if (run(ctx.db, 'INSERT INTO partner_links (partner_id, campaign, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING', a.partner.id, input.campaign, ctx.now()) === 0) {
    throw conflict('campaign_exists', 'This campaign already exists')
  }
  auditActor(ctx, a, 'link.add', input.campaign)
  return { campaigns: campaignStats(ctx, a.partner) }
}

export function removeCampaign(ctx: AppContext, user: string, slug: string, campaign: string): void {
  const a = partnerActor(ctx, user, slug, 'links')
  takeWrite(ctx, user)
  if (run(ctx.db, 'DELETE FROM partner_links WHERE partner_id = ? AND campaign = ?', a.partner.id, campaign) === 0) throw notFound('campaign_not_found', 'Campaign not found')
  auditActor(ctx, a, 'link.remove', campaign)
}

const seen = new WeakMap<AppContext, Map<string, number>>()

/** Doppelte Zählung derselben IP+UA in 10 min verhindern (nur RAM, Schlüssel gehasht). `true` = zählen. */
function firstSeen(ctx: AppContext, key: string): boolean {
  let m = seen.get(ctx)
  if (!m) {
    m = new Map()
    seen.set(ctx, m)
  }
  const t = ctx.now()
  if (m.size > 50_000) for (const [k, until] of m) if (until <= t) m.delete(k)
  const h = sha256Hex(key)
  const until = m.get(h)
  if (until !== undefined && until > t) return false
  m.set(h, t + COUNT_DEDUPE_MS)
  return true
}

/** Aktiver Partner + bekannte Kampagne (unbekannte → ohne Kampagne). `null` = unbekannt/pausiert. */
export function linkTarget(ctx: AppContext, slug: string, rawCampaign: unknown): { partner: PartnerRow, campaign: string } | null {
  const p = PARTNER_SLUG.test(slug) ? partnerBySlug(ctx, slug) : undefined
  if (!p || p.state !== 'active') return null
  const c = typeof rawCampaign === 'string' && CAMPAIGN.test(rawCampaign) ? rawCampaign : ''
  const known = c !== '' && one(ctx.db, 'SELECT 1 AS x FROM partner_links WHERE partner_id = ? AND campaign = ?', p.id, c) !== undefined
  return { partner: p, campaign: known ? c : '' }
}

function bumpLink(ctx: AppContext, partnerId: number, campaign: string, field: 'clicks' | 'downloads'): void {
  run(
    ctx.db,
    `INSERT INTO partner_link_stats (partner_id, campaign, day, ${field}) VALUES (?, ?, ?, 1)
     ON CONFLICT(partner_id, campaign, day) DO UPDATE SET ${field} = ${field} + 1`,
    partnerId, campaign, dayOf(ctx.now()),
  )
}

/** Klick auf `/go/<slug>` zählen; liefert das Ziel der Weiterleitung. */
export function countClick(ctx: AppContext, slug: string, rawCampaign: unknown, client: { ip: string, ua: string }): string {
  const t = linkTarget(ctx, slug, rawCampaign)
  if (!t) return '/download'
  if (firstSeen(ctx, `click|${client.ip}|${client.ua}|${t.partner.id}|${t.campaign}`)) bumpLink(ctx, t.partner.id, t.campaign, 'clicks')
  return `/download?partner=${encodeURIComponent(t.partner.slug)}${t.campaign ? `&c=${encodeURIComponent(t.campaign)}` : ''}`
}

/** Download-Knopf auf der Download-Seite nach `/go` (gleicher Partner, gleiche Kampagne aus der Adresse). */
export function countDownload(ctx: AppContext, slug: string, rawCampaign: unknown, client: { ip: string, ua: string }): boolean {
  const t = linkTarget(ctx, slug, rawCampaign)
  if (!t) return false
  if (!firstSeen(ctx, `download|${client.ip}|${client.ua}|${t.partner.id}|${t.campaign}`)) return false
  bumpLink(ctx, t.partner.id, t.campaign, 'downloads')
  return true
}

// ---------------------------------------------------------------- Webhook (Rechte-Prüfung, Logik in partner-webhooks.ts)

export function webhookActor(ctx: AppContext, user: string, slug: string, action: string): PartnerActor {
  const a = partnerActor(ctx, user, slug, 'webhook')
  takeWrite(ctx, user)
  auditActor(ctx, a, `webhook.${action}`)
  return a
}

// ---------------------------------------------------------------- Mitglieder

function resolvePlayer(ctx: AppContext, input: { uuid?: string, name?: string }): string {
  const uuid = input.uuid ? normalizeUuid(input.uuid) : null
  const u = uuid ? getUser(ctx, uuid) : input.name && /^[A-Za-z0-9_]{1,16}$/.test(input.name) ? getUserByName(ctx, input.name) : undefined
  if (!u) throw notFound('player_not_found', 'No TRS player with this name or UUID')
  return u.uuid
}

/** Mitglied einladen (nur der Inhaber; nicht im Team-Modus). Der Eingeladene muss annehmen. */
export function inviteMember(ctx: AppContext, user: string, slug: string, raw: unknown): { members: PartnerMemberView[] } {
  const a = partnerActor(ctx, user, slug, 'owner')
  if (!a.owner) throw forbidden('partner_team_forbidden', 'Only the owner can invite members')
  takeWrite(ctx, user)
  const input = parse(memberInviteBody, raw)
  const target = resolvePlayer(ctx, input)
  if (target === a.partner.owner_uuid) throw badRequest('invalid_request', 'The owner is already a member')
  if (one(ctx.db, 'SELECT 1 AS x FROM partner_members WHERE partner_id = ? AND uuid = ?', a.partner.id, target)) throw conflict('member_exists', 'This player is already a member or invited')
  if (one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM partner_members WHERE partner_id = ?', a.partner.id)!.n >= MEMBERS_MAX) {
    throw forbidden('partner_member_limit', `At most ${MEMBERS_MAX} members`)
  }
  const t = ctx.now()
  run(ctx.db, "INSERT INTO partner_members (partner_id, uuid, rights, status, created_at) VALUES (?, ?, ?, 'pending', ?)", a.partner.id, target, rightsJson(input.rights), t)
  ctx.events.publish(target, { type: 'partner_member_invite', partner: partnerRef(a.partner), rights: PARTNER_RIGHTS.filter((r) => input.rights.includes(r)), createdAt: iso(t) }, { meOnly: true })
  return { members: membersOf(ctx, a.partner.id) }
}

export function updateMember(ctx: AppContext, user: string, slug: string, member: string, raw: unknown): { members: PartnerMemberView[] } {
  const a = partnerActor(ctx, user, slug, 'owner')
  takeWrite(ctx, user)
  const input = parse(memberRightsBody, raw)
  if (run(ctx.db, 'UPDATE partner_members SET rights = ? WHERE partner_id = ? AND uuid = ?', rightsJson(input.rights), a.partner.id, member) === 0) {
    throw notFound('member_not_found', 'Member not found')
  }
  auditActor(ctx, a, 'member.update', `${member}: ${input.rights.join(',')}`)
  return { members: membersOf(ctx, a.partner.id) }
}

export function removeMember(ctx: AppContext, user: string, slug: string, member: string): void {
  const a = partnerActor(ctx, user, slug, 'owner')
  takeWrite(ctx, user)
  if (run(ctx.db, 'DELETE FROM partner_members WHERE partner_id = ? AND uuid = ?', a.partner.id, member) === 0) throw notFound('member_not_found', 'Member not found')
  auditActor(ctx, a, 'member.remove', member)
  ctx.events.publish(member, { type: 'partner_member_removed', partner: partnerRef(a.partner) }, { meOnly: true })
}

/** Einladung annehmen/ablehnen (der Eingeladene). */
export function answerInvite(ctx: AppContext, user: string, slug: string, raw: unknown): MyPartnerState {
  const p = requirePartner(ctx, slug)
  const input = parse(inviteAnswerBody, raw)
  const m = one<MemberRow>(ctx.db, "SELECT * FROM partner_members WHERE partner_id = ? AND uuid = ? AND status = 'pending'", p.id, user)
  if (!m) throw notFound('invite_not_found', 'Invitation not found')
  if (input.accept) {
    run(ctx.db, "UPDATE partner_members SET status = 'active', accepted_at = ? WHERE partner_id = ? AND uuid = ?", ctx.now(), p.id, user)
    notifyMembers(ctx, p, 'member_joined')
  } else {
    run(ctx.db, 'DELETE FROM partner_members WHERE partner_id = ? AND uuid = ?', p.id, user)
  }
  return myPartnerState(ctx, user)
}

/** Selbst austreten. */
export function leavePartner(ctx: AppContext, user: string, slug: string): void {
  const p = requirePartner(ctx, slug)
  if (run(ctx.db, "DELETE FROM partner_members WHERE partner_id = ? AND uuid = ? AND status = 'active'", p.id, user) === 0) {
    throw notFound('member_not_found', 'You are not a member of this partner')
  }
  notifyMembers(ctx, p, 'member_left')
}

// ---------------------------------------------------------------- Team (§44.7)

export interface AdminPartnerView {
  slug: string
  name: string
  category: PartnerCategory
  state: PartnerState
  prefix: string
  code: string | null
  codes: FixedCodeView[]
  logo: string | null
  owner: { uuid: string, name: string } | null
  quota: { total: number, used: number }
  supporters: number
  openRequests: number
  items: PartnerCosmeticRef[]
  server: { name: string, address: string, hidden: boolean, online: boolean } | null
  serverAllowed: boolean
  wikiUrl: string | null
  text: { de: string, en: string }
  links: PartnerLink[]
  createdAt: string
}

function adminView(ctx: AppContext, p: PartnerRow): AdminPartnerView {
  const s = liveServer(ctx, p.id)
  return {
    slug: p.slug,
    name: p.name,
    category: p.category,
    state: p.state,
    prefix: p.prefix,
    code: fixedCode(ctx, p.id),
    codes: fixedCodeViews(ctx, p.id),
    logo: logoPath(p.slug, p.logo_sha),
    owner: p.owner_uuid ? { uuid: p.owner_uuid, name: nameOf(ctx, p.owner_uuid) } : null,
    quota: { total: p.quota_total, used: p.quota_used },
    supporters: supporterCount(ctx, p.id),
    openRequests: one<{ n: number }>(ctx.db, "SELECT COUNT(*) AS n FROM partner_requests WHERE partner_id = ? AND status = 'open'", p.id)!.n,
    items: itemsOf(ctx, p.id),
    server: s ? { name: s.name, address: s.address, hidden: s.hidden === 1, online: s.online === 1 } : null,
    serverAllowed: p.server_allowed === 1,
    wikiUrl: p.wiki_url,
    text: { de: p.text_de, en: p.text_en },
    links: parseLinks(p.links),
    createdAt: iso(p.created_at),
  }
}

export function adminListPartners(ctx: AppContext, staff: Staff): { partners: AdminPartnerView[] } {
  assertCan(staff, 'partners.manage')
  return { partners: all<PartnerRow>(ctx.db, 'SELECT * FROM partners ORDER BY name COLLATE NOCASE, id').map((p) => adminView(ctx, p)) }
}

export function adminGetPartner(ctx: AppContext, staff: Staff, slug: string): { partner: AdminPartnerView } {
  assertCan(staff, 'partners.manage')
  return { partner: adminView(ctx, requirePartner(ctx, slug)) }
}

function resolveOwner(ctx: AppContext, input: string): string {
  const uuid = normalizeUuid(input)
  return resolvePlayer(ctx, uuid ? { uuid } : { name: input })
}

export function adminCreatePartner(ctx: AppContext, staff: Staff, raw: unknown): { partner: AdminPartnerView } {
  assertCan(staff, 'partners.manage')
  const input = parse(adminCreateBody, raw)
  const owner = resolveOwner(ctx, input.owner)
  if (partnerBySlug(ctx, input.slug)) throw conflict('slug_taken', 'This address is already taken')
  if (one(ctx.db, 'SELECT 1 AS x FROM partners WHERE prefix = ?', input.prefix)) throw conflict('prefix_taken', 'This prefix is already taken')
  checkFixedCodeFree(ctx, input.code, null)
  const t = ctx.now()
  tx(ctx.db, () => {
    const id = one<{ id: number }>(
      ctx.db,
      `INSERT INTO partners (slug, name, category, state, prefix, owner_uuid, quota_total, server_allowed, created_at, updated_at)
       VALUES (?, ?, ?, 'active', ?, ?, ?, ?, ?, ?) RETURNING id`,
      input.slug, input.name, input.category, input.prefix, owner, input.quota, input.serverAllowed ? 1 : 0, t, t,
    )!.id
    run(ctx.db, "INSERT INTO partner_codes (partner_id, code, kind, main, created_at) VALUES (?, ?, 'fixed', 1, ?)", id, input.code, t)
    audit(ctx, staff.uuid, 'partner.create', auditTarget(input), `${input.name}, code ${input.code}, quota ${input.quota}${input.serverAllowed ? ', server allowed' : ''}`)
  })
  clearPartnerStatsCache(ctx)
  return { partner: adminView(ctx, partnerBySlug(ctx, input.slug)!) }
}

export function adminUpdatePartner(ctx: AppContext, staff: Staff, slug: string, raw: unknown): { partner: AdminPartnerView } {
  assertCan(staff, 'partners.manage')
  const input = parse(adminUpdateBody, raw)
  const p = requirePartner(ctx, slug)
  if (input.code !== undefined) checkFixedCodeFree(ctx, input.code, p.id)
  if (input.prefix !== undefined && input.prefix !== p.prefix && one(ctx.db, 'SELECT 1 AS x FROM partners WHERE prefix = ?', input.prefix)) {
    throw conflict('prefix_taken', 'This prefix is already taken')
  }
  const owner = input.owner !== undefined ? resolveOwner(ctx, input.owner) : undefined
  const t = ctx.now()
  const parts: string[] = []
  tx(ctx.db, () => {
    const set = (col: string, v: string | number | null, label: string) => {
      run(ctx.db, `UPDATE partners SET ${col} = ?, updated_at = ? WHERE id = ?`, v, t, p.id)
      parts.push(label)
    }
    if (input.name !== undefined) set('name', input.name, `name ${input.name}`)
    if (input.category !== undefined) set('category', input.category, `category ${input.category}`)
    if (input.state !== undefined && input.state !== p.state) set('state', input.state, `state ${input.state}`)
    if (input.prefix !== undefined) set('prefix', input.prefix, `prefix ${input.prefix}`)
    if (owner !== undefined) {
      set('owner_uuid', owner, `owner ${owner}`)
      run(ctx.db, 'DELETE FROM partner_members WHERE partner_id = ? AND uuid = ?', p.id, owner)
    }
    if (input.wikiUrl !== undefined) set('wiki_url', input.wikiUrl, 'wiki')
    if (input.text !== undefined) {
      set('text_de', input.text.de, 'text')
      run(ctx.db, 'UPDATE partners SET text_en = ? WHERE id = ?', input.text.en, p.id)
    }
    if (input.links !== undefined) set('links', JSON.stringify(input.links), `links (${input.links.length})`)
    if (input.serverAllowed !== undefined && input.serverAllowed !== (p.server_allowed === 1)) {
      set('server_allowed', input.serverAllowed ? 1 : 0, `server ${input.serverAllowed ? 'allowed' : 'not allowed'}`)
    }
    if (input.code !== undefined) {
      setMainCode(ctx, p.id, input.code, t)
      parts.push(`code ${input.code}`)
    }
    audit(ctx, staff.uuid, input.state && input.state !== p.state ? (input.state === 'paused' ? 'partner.pause' : 'partner.resume') : 'partner.update', auditTarget(p), parts.join(', '))
  })
  const after = partnerBySlug(ctx, slug)!
  clearPartnerStatsCache(ctx)
  if (input.state !== undefined && input.state !== p.state) notifyMembers(ctx, after, input.state === 'paused' ? 'paused' : 'resumed')
  else notifyMembers(ctx, after, 'updated')
  return { partner: adminView(ctx, after) }
}

// ---------------------------------------------------------------- Feste Codes (Team)

function fixedCodesChanged(ctx: AppContext, staff: Staff, p: PartnerRow, action: string, detail: string): { partner: AdminPartnerView } {
  audit(ctx, staff.uuid, `partner.code.${action}`, auditTarget(p), detail)
  clearPartnerStatsCache(ctx)
  notifyMembers(ctx, p, 'updated')
  return { partner: adminView(ctx, p) }
}

/** Weiteren festen Code anlegen (`main: true` macht ihn gleich zum Haupt-Code). */
export function adminAddFixedCode(ctx: AppContext, staff: Staff, slug: string, raw: unknown): { partner: AdminPartnerView } {
  assertCan(staff, 'partners.manage')
  const input = parse(fixedCodeBody, raw)
  const p = requirePartner(ctx, slug)
  checkFixedCodeFree(ctx, input.code, p.id)
  const t = ctx.now()
  tx(ctx.db, () => {
    insertFixedCode(ctx, p.id, input.code, t)
    if (input.main) setMainCode(ctx, p.id, input.code, t)
  })
  return fixedCodesChanged(ctx, staff, p, 'add', `${input.code}${input.main ? ' (main)' : ''}`)
}

/** Festen Code (de)aktivieren oder zum Haupt-Code machen. Der Haupt-Code bleibt immer aktiv. */
export function adminUpdateFixedCode(ctx: AppContext, staff: Staff, slug: string, rawCode: string, raw: unknown): { partner: AdminPartnerView } {
  assertCan(staff, 'partners.manage')
  const input = parse(fixedCodePatchBody, raw)
  const p = requirePartner(ctx, slug)
  const k = fixedCodeRow(ctx, p.id, rawCode.trim().toUpperCase())
  if (!k) throw notFound('code_not_found', 'Fixed code not found')
  if ((input.main === true || k.main === 1) && input.active === false) throw conflict('partner_code_main', 'The main code cannot be deactivated')
  const t = ctx.now()
  tx(ctx.db, () => {
    if (input.main) setMainCode(ctx, p.id, k.code, t)
    else if (input.active !== undefined) run(ctx.db, 'UPDATE partner_codes SET blocked = ? WHERE id = ?', input.active ? 0 : 1, k.id)
  })
  const what = input.main ? 'main' : input.active ? 'active' : 'inactive'
  return fixedCodesChanged(ctx, staff, p, 'update', `${k.code} ${what}`)
}

/** Festen Code löschen – nur Zusatz-Codes ohne Einlösungen (sonst deaktivieren, die Statistik bleibt). */
export function adminDeleteFixedCode(ctx: AppContext, staff: Staff, slug: string, rawCode: string): { partner: AdminPartnerView } {
  assertCan(staff, 'partners.manage')
  const p = requirePartner(ctx, slug)
  const k = fixedCodeRow(ctx, p.id, rawCode.trim().toUpperCase())
  if (!k) throw notFound('code_not_found', 'Fixed code not found')
  if (k.main === 1) throw conflict('partner_code_main', 'The main code cannot be removed')
  if (one(ctx.db, 'SELECT 1 AS x FROM partner_redemptions WHERE code_id = ?', k.id)) throw conflict('partner_code_redeemed', 'The code has redemptions – deactivate it instead')
  run(ctx.db, 'DELETE FROM partner_codes WHERE id = ?', k.id)
  return fixedCodesChanged(ctx, staff, p, 'remove', k.code)
}

/** Partner löschen: Codes, Anträge, Statistik weg; Spieler behalten ihre Kosmetik. */
export function adminDeletePartner(ctx: AppContext, staff: Staff, slug: string): void {
  assertCan(staff, 'partners.manage')
  const p = requirePartner(ctx, slug)
  const files = new Set<string>()
  if (p.logo_sha) files.add(p.logo_sha)
  for (const r of all<{ data: string }>(ctx.db, "SELECT data FROM partner_requests WHERE partner_id = ? AND kind IN ('logo', 'cape')", p.id)) {
    const sha = parseJson<{ sha?: string }>(r.data, {}).sha
    if (sha) files.add(sha)
  }
  tx(ctx.db, () => {
    run(ctx.db, 'DELETE FROM partners WHERE id = ?', p.id)
    audit(ctx, staff.uuid, 'partner.delete', auditTarget(p), p.name)
  })
  clearPartnerStatsCache(ctx)
  for (const sha of files) dropFileIfUnused(ctx, sha)
}

/** Kontingent-Paket freigeben (+500/+1000/+5000); schließt einen offenen Nachschub-Antrag. */
export function adminGrantQuota(ctx: AppContext, staff: Staff, slug: string, raw: unknown): { partner: AdminPartnerView } {
  assertCan(staff, 'partners.manage')
  const input = parse(quotaBody, raw)
  const p = requirePartner(ctx, slug)
  const t = ctx.now()
  tx(ctx.db, () => {
    run(ctx.db, 'UPDATE partners SET quota_total = quota_total + ?, updated_at = ? WHERE id = ?', input.amount, t, p.id)
    // Wieder genug frei → `quota.low` darf erneut kommen.
    run(ctx.db, 'UPDATE partners SET quota_low_sent = 0 WHERE id = ? AND (quota_total - quota_used) * 10 >= quota_total', p.id)
    run(ctx.db, "UPDATE partner_requests SET status = 'accepted', decided_at = ?, decided_by = ? WHERE partner_id = ? AND kind = 'quota' AND status = 'open'", t, staff.uuid, p.id)
    audit(ctx, staff.uuid, 'partner.quota', auditTarget(p), `+${input.amount}`)
  })
  notifyMembers(ctx, p, 'quota_granted')
  return { partner: adminView(ctx, partnerBySlug(ctx, slug)!) }
}

export function adminAddItem(ctx: AppContext, staff: Staff, slug: string, raw: unknown): { partner: AdminPartnerView } {
  assertCan(staff, 'partners.manage')
  const input = parse(itemBody, raw)
  const p = requirePartner(ctx, slug)
  if (input.kind === 'cape') {
    const c = getCape(ctx, input.id)
    // Nur Katalog-Teile (Standard-Umhänge oder Partner-Umhänge), keine Uploads von Spielern.
    if (!c || c.retired || c.status !== 'approved' || (c.kind === 'upload' && !c.partner_id) || c.unlock === 'free') throw notFound('item_not_found', 'Cape not found or not assignable')
  } else {
    const c = getCosmetic(ctx, input.id)
    if (!c || c.retired || c.status !== 'approved' || c.kind !== 'builtin' || c.slot === 'emote' || c.unlock === 'free') throw notFound('item_not_found', 'Cosmetic not found or not assignable')
  }
  const granted = tx(ctx.db, () => {
    run(ctx.db, 'INSERT INTO partner_items (partner_id, kind, item_id, created_at) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING', p.id, input.kind, input.id, ctx.now())
    return grantToSupporters(ctx, p.id, input.kind, input.id)
  })
  audit(ctx, staff.uuid, 'partner.item.add', auditTarget(p), `${input.kind} ${input.id} (${granted} supporters)`)
  return { partner: adminView(ctx, p) }
}

export function adminRemoveItem(ctx: AppContext, staff: Staff, slug: string, kind: string, id: string): { partner: AdminPartnerView } {
  assertCan(staff, 'partners.manage')
  const p = requirePartner(ctx, slug)
  if (run(ctx.db, 'DELETE FROM partner_items WHERE partner_id = ? AND kind = ? AND item_id = ?', p.id, kind, id) === 0) throw notFound('item_not_found', 'Item not assigned')
  audit(ctx, staff.uuid, 'partner.item.remove', auditTarget(p), `${kind} ${id}`)
  return { partner: adminView(ctx, p) }
}

/** Zuordenbare Katalog-Teile (Umhänge außer freien/Spieler-Uploads, Kosmetik außer freien/Emotes). */
export function adminCatalog(ctx: AppContext, staff: Staff): { items: PartnerCosmeticRef[] } {
  assertCan(staff, 'partners.manage')
  const items: PartnerCosmeticRef[] = []
  for (const c of all<{ id: string }>(
    ctx.db,
    "SELECT id FROM capes WHERE retired = 0 AND status = 'approved' AND unlock <> 'free' AND (kind = 'builtin' OR partner_id IS NOT NULL) ORDER BY partner_id IS NULL, sort, name",
  )) {
    const cape = getCape(ctx, c.id)!
    items.push({ kind: 'cape', id: cape.id, name: cape.name, previewUrl: capeUrl(ctx, cape) })
  }
  for (const c of all<{ id: string }>(
    ctx.db,
    "SELECT id FROM cosmetics WHERE retired = 0 AND status = 'approved' AND kind = 'builtin' AND slot <> 'emote' AND unlock <> 'free' ORDER BY sort, name",
  )) {
    const cos = getCosmetic(ctx, c.id)!
    items.push({ kind: 'cosmetic', id: cos.id, name: cos.name, previewUrl: cosmeticView(ctx, cos).texture?.url ?? null })
  }
  return { items }
}

export interface AdminRequestView extends RequestView {
  partner: PartnerRef
  createdBy: { uuid: string, name: string } | null
  /** Bei `server`: Ergebnis eines Pings (nur offene Anträge, nur Anzeige). */
  current?: Record<string, unknown> | null
}

export function adminListRequests(ctx: AppContext, staff: Staff, rawQuery: unknown): { requests: AdminRequestView[] } {
  assertCan(staff, 'partners.manage')
  const q = parse(requestListQuery, rawQuery)
  const status = q.status ?? 'open'
  const rows = all<RequestRow & { slug: string, pname: string }>(
    ctx.db,
    `SELECT r.*, p.slug, p.name AS pname FROM partner_requests r JOIN partners p ON p.id = r.partner_id
     WHERE r.status = ? ORDER BY r.created_at ${status === 'open' ? 'ASC' : 'DESC'}, r.id LIMIT 200`,
    status,
  )
  return {
    requests: rows.map((r) => ({
      ...requestView(r),
      partner: { slug: r.slug, name: r.pname },
      createdBy: r.created_by ? { uuid: r.created_by, name: nameOf(ctx, r.created_by) } : null,
    })),
  }
}

export function adminRequestImage(ctx: AppContext, staff: Staff, id: number): { png: Buffer, sha256: string, public: boolean } {
  assertCan(staff, 'partners.manage')
  const r = one<RequestRow>(ctx.db, 'SELECT * FROM partner_requests WHERE id = ?', id)
  if (!r) throw notFound('request_not_found', 'Request not found')
  return requestImage(ctx, r)
}

function newServerId(): string {
  return `ps_${randomBytes(8).toString('hex')}`
}

/**
 * Antrag entscheiden. Annehmen übernimmt die neue Fassung (Profil, Logo, Code, Server, Umhang als Partner-Teil,
 * Kontingent); `promo` und `cosmetic_wish` werden nur als eingeplant/angenommen markiert. Ablehnen mit Grund.
 */
export function adminDecideRequest(ctx: AppContext, staff: Staff, id: number, raw: unknown): { request: AdminRequestView } {
  assertCan(staff, 'partners.manage')
  const input = parse(decisionBody, raw)
  const r = one<RequestRow>(ctx.db, "SELECT * FROM partner_requests WHERE id = ? AND status = 'open'", id)
  if (!r) throw notFound('request_not_found', 'Request not found or already decided')
  const p = partnerById(ctx, r.partner_id)!
  const data = parseJson<Record<string, unknown>>(r.data, {})
  const t = ctx.now()
  if (input.status === 'rejected' && !input.reason) throw badRequest('reason_required', 'Rejecting needs a reason')
  tx(ctx.db, () => {
    if (input.status === 'accepted') {
      switch (r.kind) {
        case 'quota': {
          const amount = Number(data.amount) || 0
          run(ctx.db, 'UPDATE partners SET quota_total = quota_total + ?, updated_at = ? WHERE id = ?', amount, t, p.id)
          run(ctx.db, 'UPDATE partners SET quota_low_sent = 0 WHERE id = ? AND (quota_total - quota_used) * 10 >= quota_total', p.id)
          break
        }
        case 'code_change': {
          const code = String(data.code)
          checkFixedCodeFree(ctx, code, p.id)
          if (data.action === 'add') insertFixedCode(ctx, p.id, code, t)
          else setMainCode(ctx, p.id, code, t)
          break
        }
        case 'profile': {
          const text = data.text as { de: string, en: string }
          run(ctx.db, 'UPDATE partners SET text_de = ?, text_en = ?, links = ?, updated_at = ? WHERE id = ?', text.de, text.en, JSON.stringify(data.links ?? []), t, p.id)
          break
        }
        case 'logo':
          run(ctx.db, 'UPDATE partners SET logo_sha = ?, updated_at = ? WHERE id = ?', String(data.sha), t, p.id)
          break
        case 'server': {
          if (p.server_allowed !== 1) throw serverNotAllowed()
          const old = liveServer(ctx, p.id)
          const s = data as { name: string, address: string, minVersion: string, maxVersion: string, motd: string }
          if (old) {
            const moved = old.address !== s.address
            run(
              ctx.db,
              `UPDATE partner_servers SET name = ?, address = ?, min_version = ?, max_version = ?, motd = ?, approved_at = ?, updated_at = ?${moved ? ', online = 0, last_online_at = NULL, last_checked_at = NULL' : ''} WHERE id = ?`,
              s.name, s.address, s.minVersion, s.maxVersion, s.motd, t, t, old.id,
            )
          } else {
            run(
              ctx.db,
              'INSERT INTO partner_servers (id, partner_id, name, address, min_version, max_version, motd, approved_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
              newServerId(), p.id, s.name, s.address, s.minVersion, s.maxVersion, s.motd, t, t,
            )
          }
          break
        }
        case 'cape': {
          const capeId = newUploadCapeId()
          let png: Buffer
          let size: { width: number, height: number, frames: number, frameTimeMs: number | null }
          if (typeof data.capeId === 'string') {
            // Gewählter eigener Umhang: Kopie als Partner-Umhang, der Upload des Spielers bleibt seiner.
            const src = getCape(ctx, data.capeId)
            const file = src ? join(ctx.capeDir, `${src.id}.png`) : ''
            if (!src || src.kind !== 'upload' || src.status !== 'approved' || src.retired || !existsSync(file)) {
              throw conflict('cape_unavailable', 'The selected cape is no longer available')
            }
            png = readFileSync(file)
            size = { width: src.width, height: src.height, frames: src.frames, frameTimeMs: src.frames > 1 ? src.frame_time_ms : null }
          } else {
            png = readFileSync(join(partnerFileDir(ctx), `${String(data.sha)}.png`))
            size = { width: Number(data.width), height: Number(data.height), frames: Number(data.frames), frameTimeMs: data.frameTimeMs === null ? null : Number(data.frameTimeMs) }
          }
          writeAtomic(ctx.capeDir, `${capeId}.png`, png)
          run(
            ctx.db,
            `INSERT INTO capes (id, kind, name, owner_uuid, status, unlock, sha256, width, height, frames, frame_time_ms, sort, retired, created_at, reviewed_at, reviewed_by, partner_id)
             VALUES (?, 'upload', ?, NULL, 'approved', 'code', ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?)`,
            capeId, String(data.name).slice(0, 32), sha256Hex(png), size.width, size.height, size.frames, size.frameTimeMs, t, t, staff.uuid, p.id,
          )
          run(ctx.db, "INSERT INTO partner_items (partner_id, kind, item_id, created_at) VALUES (?, 'cape', ?, ?) ON CONFLICT DO NOTHING", p.id, capeId, t)
          grantToSupporters(ctx, p.id, 'cape', capeId)
          break
        }
        default:
          break
      }
    }
    run(
      ctx.db,
      'UPDATE partner_requests SET status = ?, reason = ?, decided_at = ?, decided_by = ? WHERE id = ?',
      input.status, input.reason ?? null, t, staff.uuid, r.id,
    )
    audit(ctx, staff.uuid, `partner.request.${input.status === 'accepted' ? 'accept' : 'reject'}`, auditTarget(p), `${r.kind}${input.reason ? `: ${input.reason}` : ''}`)
  })
  if (r.kind === 'logo' || r.kind === 'cape') {
    const sha = typeof data.sha === 'string' ? data.sha : undefined
    // Umhang liegt jetzt unter capes/, die Antragsdatei wird frei.
    dropFileIfUnused(ctx, sha)
  }
  notifyMembers(ctx, p, input.status === 'accepted' ? 'request_accepted' : 'request_rejected', { reason: input.reason, requestKind: r.kind })
  const row = one<RequestRow>(ctx.db, 'SELECT * FROM partner_requests WHERE id = ?', r.id)!
  return { request: { ...requestView(row), partner: partnerRef(p), createdBy: row.created_by ? { uuid: row.created_by, name: nameOf(ctx, row.created_by) } : null } }
}

export const adminCapeQuery = z.strictObject({
  name: oneLine(1, 32),
  frames: z.coerce.number().int().min(1).max(32).optional(),
  frameTimeMs: z.coerce.number().int().min(50).max(10_000).optional(),
})

/**
 * Team lädt einen Partner-Umhang direkt hoch (Regeln wie §5.6, HD bis 1024×512 je Bild, bis 32 Bilder, ≤ 20 MB):
 * versteckt (nur Besitzer sehen ihn im Katalog), sofort zugeordnet, aktuelle Unterstützer bekommen ihn gleich.
 */
export function adminUploadPartnerCape(ctx: AppContext, staff: Staff, slug: string, body: Buffer, rawQuery: unknown): { partner: AdminPartnerView, cape: { id: string, name: string, width: number, height: number, frames: number, frameTimeMs: number | null, granted: number } } {
  assertCan(staff, 'partners.manage')
  const q = parse(adminCapeQuery, rawQuery)
  const p = requirePartner(ctx, slug)
  const clean = sanitizeCapeUpload(body, { frames: q.frames })
  if (clean.frames > 1 && q.frameTimeMs === undefined) throw badRequest('frame_time_required', 'Animated capes need the frameTimeMs query parameter')
  const frameTimeMs = clean.frames > 1 ? q.frameTimeMs! : null
  const capeId = newUploadCapeId()
  const t = ctx.now()
  writeAtomic(ctx.capeDir, `${capeId}.png`, clean.png)
  let granted: number
  try {
    granted = tx(ctx.db, () => {
      run(
        ctx.db,
        `INSERT INTO capes (id, kind, name, owner_uuid, status, unlock, hidden, sha256, width, height, frames, frame_time_ms, sort, retired, created_at, reviewed_at, reviewed_by, partner_id)
         VALUES (?, 'upload', ?, NULL, 'approved', 'code', 1, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?)`,
        capeId, q.name, sha256Hex(clean.png), clean.width, clean.height, clean.frames, frameTimeMs, t, t, staff.uuid, p.id,
      )
      run(ctx.db, "INSERT INTO partner_items (partner_id, kind, item_id, created_at) VALUES (?, 'cape', ?, ?)", p.id, capeId, t)
      return grantToSupporters(ctx, p.id, 'cape', capeId)
    })
  } catch (err) {
    rmSync(join(ctx.capeDir, `${capeId}.png`), { force: true })
    throw err
  }
  audit(ctx, staff.uuid, 'partner.cape.upload', auditTarget(p), `${q.name} (${capeId}, ${clean.frames} frame(s), ${granted} supporters)`)
  notifyMembers(ctx, p, 'updated')
  return {
    partner: adminView(ctx, p),
    cape: { id: capeId, name: q.name, width: clean.width, height: clean.height, frames: clean.frames, frameTimeMs, granted },
  }
}

/** Partner-Server ausblenden/wieder zeigen (z. B. Werbung im Namen, EULA-Verstoß). */
export function adminSetServerHidden(ctx: AppContext, staff: Staff, slug: string, raw: unknown): { partner: AdminPartnerView } {
  assertCan(staff, 'partners.manage')
  const input = parse(serverHiddenBody, raw)
  const p = requirePartner(ctx, slug)
  if (run(ctx.db, 'UPDATE partner_servers SET hidden = ?, updated_at = ? WHERE partner_id = ?', input.hidden ? 1 : 0, ctx.now(), p.id) === 0) {
    throw notFound('server_not_found', 'This partner has no server')
  }
  audit(ctx, staff.uuid, input.hidden ? 'partner.server.hide' : 'partner.server.show', auditTarget(p))
  notifyMembers(ctx, p, 'updated')
  return { partner: adminView(ctx, p) }
}

export function adminDeleteServer(ctx: AppContext, staff: Staff, slug: string): void {
  assertCan(staff, 'partners.manage')
  const p = requirePartner(ctx, slug)
  if (run(ctx.db, 'DELETE FROM partner_servers WHERE partner_id = ?', p.id) === 0) throw notFound('server_not_found', 'This partner has no server')
  audit(ctx, staff.uuid, 'partner.server.delete', auditTarget(p))
  notifyMembers(ctx, p, 'updated')
}

/** Offene Anträge (Zähler in der Team-Navigation). */
export function partnerCounts(ctx: AppContext): { requests: number } {
  return { requests: one<{ n: number }>(ctx.db, "SELECT COUNT(*) AS n FROM partner_requests WHERE status = 'open'")!.n }
}

/** Für Tests: Code-/Schlüssel-Erzeugung. */
export const _internal = { newOneTimeCode, newApiKey }
