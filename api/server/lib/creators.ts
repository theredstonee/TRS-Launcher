import { readFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { z } from 'zod'
import { capeUrl, capeWearers, getCape, grantCape, removeCape, writeAtomic, type CapeRow } from './capes'
import type { AppContext } from './context'
import {
  symbolView,
  type CreatorState,
  type CreatorTier,
  type Milestone,
  type SymbolView,
} from './creator-lookup'
import { all, one, placeholders, run, tx } from './db'
import { ApiError, badRequest, conflict, forbidden, notFound, tooMany } from './errors'
import type { PlayerRef } from './events'
import { areFriends } from './friends'
import { newUploadCapeId, normalizeUuid, sha256Hex } from './ids'
import { emitCape, emitToWatchers } from './playerevents'
import { displayCode, normalizePackCode, setPackCreatorListed, type PackRow } from './packs'
import { decodeRgba, encodeRgba, inspectPng, sanitizeCapeUpload } from './png'
import { RULES } from './ratelimit'
import { assertNotSanctioned } from './sanctions'
import { capeNameSchema, plainText } from './schemas'
import { SKIN_URL_RE } from './skins'
import { assertCan, type Staff } from './team'
import { audit } from './audit'
import { ACTIVE_BANS, getUser, getUserByName } from './users'
import type { Watcher } from './watch'

export type { CreatorState, CreatorTier, Milestone, SymbolView } from './creator-lookup'

/**
 * Creator-Programm (API.md §35): Creator (Stufe, Status), öffentliche Creator-Codes, Unterstützung durch Fans (ein Creator
 * je Fan, Wechsel frühestens 30 Tage nach dem letzten Start), Blockaden, Fan-Symbole (Prüfung durch das Team),
 * Fan-Umhänge (normale `capes`-Zeilen mit `creator_uuid`, Besitz über `user_capes`), Meilensteine, Anfragen,
 * Vorschau-Builds und Creator-Packs. Alle Eingaben laufen durch die Schemas unten.
 */

export type CreatorUpdateKind =
  | 'symbol_approved'
  | 'symbol_rejected'
  | 'cape_approved'
  | 'cape_rejected'
  | 'tier_changed'
  | 'revoked'
  | 'restored'
  | 'request_answered'
  | 'milestone'
  | 'became_creator'
  | 'helper_joined'
  | 'helper_left'

/** Rechte eines Helfers (§35.9). Anfragen, Team-Umhang, Helfer und Vorschau-Builds bleiben immer beim Creator. */
export const HELPER_RIGHTS = ['stats', 'codes', 'uploads', 'packs'] as const
export type HelperRight = (typeof HELPER_RIGHTS)[number]
/** Helfer je Stufe (angenommen + eingeladen). */
export const HELPER_LIMITS: Record<CreatorTier, number> = { starter: 1, partner: 3, top: 10 }

export const TIERS = ['starter', 'partner', 'top'] as const satisfies readonly CreatorTier[]
export const CREATOR_STATES = ['active', 'revoked', 'banned'] as const satisfies readonly CreatorState[]

const DAY = 86_400_000
/** Wechsel zu einem ANDEREN Creator frühestens so lange nach dem letzten Start. */
export const SWITCH_LOCK_MS = 30 * DAY
/** Öffentliche Creator-Codes: 5–20 Zeichen, A–Z, 0–9 und Bindestrich (nicht am Rand). */
export const CREATOR_CODE = /^[A-Z0-9][A-Z0-9-]{3,18}[A-Z0-9]$/
export const SYMBOL_MAX_BYTES = 256 * 1024
export const SYMBOL_MIN_SIZE = 16
export const SYMBOL_MAX_SIZE = 64
export const SYMBOL_MAX_FRAMES = 8
export const SYMBOL_DEFAULT_FRAME_MS = 150
/** Uploads (Symbol bzw. Fan-Umhang) je Creator in 24 h. */
export const CREATOR_UPLOADS_PER_DAY = 5

export const MILESTONES: readonly { name: Milestone, at: number }[] = [
  { name: 'bronze', at: 100 },
  { name: 'silver', at: 500 },
  { name: 'gold', at: 1000 },
  { name: 'diamond', at: 5000 },
]
const milestoneRank = (m: Milestone | null) => (m === null ? 0 : MILESTONES.findIndex((x) => x.name === m) + 1)

export interface TierLimits {
  /** Zusätzliche (aktive) Codes; `null` = unbegrenzt. */
  extraCodes: number | null
  /** Höchstzahl Einlösungen je Zusatz-Code; `null` = ohne Obergrenze. */
  maxUsesPerCode: number | null
  symbolFrames: 1 | 8
  /** Fan-Umhang: keiner, Standard (bis 512×256, 1 Frame) oder HD (bis 1024×512, bis 32 Frames). */
  fanCape: false | 'standard' | 'hd'
}

export const TIER_LIMITS: Record<CreatorTier, TierLimits> = {
  starter: { extraCodes: 1, maxUsesPerCode: 100, symbolFrames: 1, fanCape: false },
  partner: { extraCodes: 5, maxUsesPerCode: 1000, symbolFrames: 8, fanCape: 'standard' },
  top: { extraCodes: null, maxUsesPerCode: null, symbolFrames: 8, fanCape: 'hd' },
}
/** Standard-Fan-Umhang: höchstens Faktor 8 (512×256 je Frame). */
const STANDARD_CAPE_MAX_SCALE = 8

// ---------------------------------------------------------------- Zeilen

interface CreatorRow {
  uuid: string
  tier: CreatorTier
  state: CreatorState
  reason: string | null
  channels: string
  symbol_id: number | null
  fan_cape_id: string | null
  milestone: Milestone | null
  created_at: number
  updated_at: number
}

interface CodeRow {
  id: number
  creator_uuid: string
  code: string
  kind: 'main' | 'extra'
  uses: number
  max_uses: number | null
  expires_at: number | null
  active: number
  created_at: number
}

interface SymbolRow {
  id: number
  creator_uuid: string
  sha256: string
  size: number
  frames: number
  frame_ms: number
  status: 'pending' | 'approved' | 'rejected'
  reason: string | null
  created_at: number
  reviewed_at: number | null
  reviewed_by: string | null
}

interface SupportRow {
  fan_uuid: string
  creator_uuid: string | null
  last_creator_uuid: string | null
  last_started_at: number | null
  updated_at: number
}

interface RequestRow {
  id: number
  creator_uuid: string
  kind: 'upgrade' | 'team_cape'
  status: 'open' | 'accepted' | 'declined'
  data: string
  reason: string | null
  created_at: number
  decided_at: number | null
  decided_by: string | null
}

interface PreviewRow {
  id: number
  product: 'launcher' | 'client'
  version: string
  notes: string
  url: string
  sha256: string
  published_at: number
  created_by: string
}

// ---------------------------------------------------------------- Sichten

export interface Channel {
  platform: string
  url: string
  followers: number
}

export interface FanCapeRef {
  capeId: string
  name: string
  url: string
}

export interface CreatorCard {
  uuid: string
  name: string
  skin: string | null
  tier: CreatorTier
  channels: Channel[]
  symbol: SymbolView | null
  fanCape: FanCapeRef | null
  milestone: Milestone | null
}

export interface CodeView {
  code: string
  kind: 'main' | 'extra'
  uses: number
  maxUses: number | null
  expiresAt: string | null
  active: boolean
  createdAt: string
}

export interface CreatorStats {
  activeFans: number
  redemptions: number
  fanCapeHolders: number
  newPlayers: number
  packInstalls: number
  daily: { day: string, newFans: number, redemptions: number }[]
}

export interface RequestView {
  id: number
  kind: 'upgrade' | 'team_cape'
  status: 'open' | 'accepted' | 'declined'
  createdAt: string
  reason: string | null
}

export interface CreatorPackRef {
  code: string
  name: string
  installs: number
  listed: boolean
}

export interface CreatorSelf {
  uuid: string
  name: string
  tier: CreatorTier
  state: CreatorState
  reason: string | null
  since: string
  channels: Channel[]
  codes: CodeView[]
  limits: TierLimits
  /** `null` nur für Helfer ohne das Recht `stats`. */
  stats: CreatorStats | null
  milestone: Milestone | null
  nextMilestone: { name: Milestone, at: number } | null
  symbol: { current: SymbolView | null, pending: SymbolView | null, rejected: { reason: string | null, at: string } | null }
  fanCape: { current: FanCapeRef | null, pending: FanCapeRef | null, rejected: { reason: string | null, at: string } | null }
  requests: RequestView[]
  packs: CreatorPackRef[]
  /** Nur für den Creator selbst: seine Helfer (eingeladen und aktiv). */
  helpers?: HelperView[]
  /** Nur wenn ein Helfer die Sicht abruft (`?as=`). */
  viewer?: { helper: true, rights: HelperRight[] }
}

export interface HelperView {
  uuid: string
  name: string
  skin: string | null
  rights: HelperRight[]
  status: 'pending' | 'active'
  createdAt: string
}

interface HelperRow {
  creator_uuid: string
  helper_uuid: string
  rights: string
  status: 'pending' | 'active'
  created_at: number
  accepted_at: number | null
}

/**
 * Wer handelt im Dashboard: der Creator selbst (alle Rechte) oder ein aktiver Helfer mit `?as=<creator>` (§35.9).
 * `user` = angemeldetes Konto, `creator` = der Creator, für den gehandelt wird.
 */
export interface CreatorActor {
  user: string
  creator: CreatorRow
  helper: boolean
  rights: ReadonlySet<HelperRight>
}

export interface PreviewView {
  id: number
  product: 'launcher' | 'client'
  version: string
  notes: string
  url: string
  sha256: string
  publishedAt: string
}

const iso = (t: number) => new Date(t).toISOString()
const dayOf = (t: number) => new Date(t).toISOString().slice(0, 10)

function parseChannels(raw: string): Channel[] {
  try {
    const v = JSON.parse(raw) as unknown
    return Array.isArray(v) ? (v as Channel[]) : []
  } catch {
    return []
  }
}

function parse<S extends z.ZodType>(schema: S, raw: unknown): z.output<S> {
  const r = schema.safeParse(raw)
  if (!r.success) {
    throw badRequest('invalid_request', 'Request validation failed', {
      fields: r.error.issues.slice(0, 20).map((i) => ({ path: i.path.join('.') || '(body)', message: i.message })),
    })
  }
  return r.data
}

// ---------------------------------------------------------------- Schemas

const httpsUrl = z
  .string()
  .trim()
  .max(300)
  .refine((s) => {
    try {
      const u = new URL(s)
      return u.protocol === 'https:' && !u.username && !u.password
    } catch {
      return false
    }
  }, 'must be an https URL')

/** Mehrzeiliger Text ohne Steuerzeichen (außer Zeilenumbruch). */
const multiline = (max: number) =>
  z
    .string()
    .trim()
    .max(max)
    .transform((s) => s.replace(/\r\n?/g, '\n'))
    .refine((s) => !/[\p{Cc}\p{Cf}\p{Co}]/u.test(s.replace(/\n/g, '')), 'must not contain control characters')

export const PLATFORMS = ['youtube', 'twitch', 'tiktok', 'instagram', 'x', 'kick', 'other'] as const
const channelSchema = z.strictObject({
  platform: z.enum(PLATFORMS),
  url: httpsUrl,
  followers: z.number().int().min(0).max(10_000_000_000),
})

export const creatorCodeBody = z.strictObject({ code: z.string().trim().min(1).max(64) })
export const creatorSettingsBody = z.strictObject({ showFanSymbols: z.boolean() })
const isoTime = z.iso.datetime({ offset: true })
export const createCodeBody = z.strictObject({
  code: z.string().trim().min(1).max(32),
  maxUses: z.number().int().min(1).max(10_000_000).optional(),
  expiresAt: isoTime.optional(),
})
export const updateCodeBody = z
  .strictObject({
    active: z.boolean().optional(),
    maxUses: z.number().int().min(1).max(10_000_000).nullable().optional(),
    expiresAt: isoTime.nullable().optional(),
  })
  .refine((o) => Object.keys(o).length > 0, 'at least one field is required')
export const symbolQuery = z.strictObject({ frameMs: z.coerce.number().int().min(50).max(1000).optional(), as: z.string().max(36).optional() })
export const fanCapeQuery = z.strictObject({
  as: z.string().max(36).optional(),
  name: capeNameSchema.optional(),
  frames: z.coerce.number().int().min(1).max(32).optional(),
  frameTimeMs: z.coerce.number().int().min(50).max(10_000).optional(),
})
export const requestBody = z.discriminatedUnion('kind', [
  z.strictObject({
    kind: z.literal('upgrade'),
    channelUrl: httpsUrl,
    followers: z.number().int().min(0).max(10_000_000_000),
    note: multiline(500).optional(),
  }),
  z.strictObject({
    kind: z.literal('team_cape'),
    text: multiline(1000).refine((s) => s.length > 0, 'must not be empty'),
    attachmentUploadId: z.string().regex(/^[A-Za-z0-9_-]{1,64}$/).optional(),
  }),
])
export const packListedBody = z.strictObject({ listed: z.boolean() })
export const creatorPacksQuery = z.strictObject({
  q: z.string().trim().max(64).optional(),
  offset: z.coerce.number().int().min(0).max(10_000).default(0),
  limit: z.coerce.number().int().min(1).max(50).default(20),
})
export const rejectBody = z.strictObject({ reason: plainText(300) })
export const adminListQuery = z.strictObject({
  q: z.string().trim().max(32).optional(),
  state: z.enum(CREATOR_STATES).optional(),
})
export const adminCreateBody = z.strictObject({
  player: z.string().trim().min(1).max(36),
  tier: z.enum(TIERS).default('starter'),
  mainCode: z.string().trim().min(1).max(32).optional(),
})
export const adminUpdateBody = z
  .strictObject({
    tier: z.enum(TIERS).optional(),
    mainCode: z.string().trim().min(1).max(32).optional(),
    channels: z.array(channelSchema).max(10).optional(),
  })
  .refine((o) => Object.keys(o).length > 0, 'at least one field is required')
export const revokeBody = z.strictObject({
  reason: plainText(300),
  ban: z.boolean().default(false),
  removeFanCapes: z.boolean().default(false),
})
export const requestDecisionBody = z.strictObject({
  status: z.enum(['accepted', 'declined']),
  reason: plainText(300).optional(),
})
export const requestListQuery = z.strictObject({ status: z.enum(['open', 'accepted', 'declined']).optional() })
export const previewBody = z.strictObject({
  product: z.enum(['launcher', 'client']),
  version: z.string().trim().regex(/^[0-9A-Za-z._+-]{1,40}$/, 'invalid version'),
  notes: multiline(2000).default(''),
  url: httpsUrl,
  sha256: z.string().trim().toLowerCase().regex(/^[0-9a-f]{64}$/, 'must be a SHA-256 in hex'),
  publishedAt: isoTime.optional(),
})
/** Prüf-Eintrag: `s<id>` = Symbol, `c<capeId>` = Fan-Umhang. */
export const QUEUE_ID = /^(s\d{1,12}|cu[0-9a-f]{20})$/

// ---------------------------------------------------------------- Grundlagen

/** Eingabe → Code (groß, ohne Leerzeichen am Rand) oder `null`, wenn das Format nicht passt. */
export function normalizeCreatorCode(input: string): string | null {
  const s = input.trim().toUpperCase()
  return CREATOR_CODE.test(s) ? s : null
}

/** Standard-Haupt-Code: `TRS-<NAME>` groß, ohne Sonderzeichen (bei zu kurzen Namen aus der UUID). */
export function defaultMainCode(name: string, uuid: string): string {
  const clean = name.toUpperCase().replace(/[^A-Z0-9]/g, '')
  const base = `TRS-${clean || uuid.slice(0, 8).toUpperCase()}`.slice(0, 20)
  return CREATOR_CODE.test(base) ? base : `TRS-${uuid.slice(0, 8).toUpperCase()}`
}

function codeTaken(ctx: AppContext, code: string): boolean {
  return one(ctx.db, 'SELECT 1 AS x FROM creator_codes WHERE code = ?', code) !== undefined
}

/** Freier Code ab `base`; belegt → Zahl anhängen (`TRS-PIXEL2`, `TRS-PIXEL3`, …). */
function freeCode(ctx: AppContext, base: string): string {
  if (!codeTaken(ctx, base)) return base
  for (let n = 2; n < 10_000; n++) {
    const suffix = String(n)
    const c = `${base.slice(0, 20 - suffix.length)}${suffix}`
    if (CREATOR_CODE.test(c) && !codeTaken(ctx, c)) return c
  }
  throw conflict('creator_code_taken', 'No free code found')
}

export function symbolDir(ctx: AppContext): string {
  return join(ctx.config.dataDir, 'creator-symbols')
}

function creatorRow(ctx: AppContext, uuid: string): CreatorRow | undefined {
  return one<CreatorRow>(ctx.db, 'SELECT * FROM creators WHERE uuid = ?', uuid)
}

export function isActiveCreator(ctx: AppContext, uuid: string): boolean {
  return one(ctx.db, "SELECT 1 AS x FROM creators WHERE uuid = ? AND state = 'active'", uuid) !== undefined
}

/** Für die Creator-Routen (§35.3): nur aktive Creator. Kein Creator, entzogen oder gesperrt → 403 `creator_inactive`. */
function requireActive(ctx: AppContext, uuid: string): CreatorRow {
  const c = creatorRow(ctx, uuid)
  if (!c || c.state !== 'active') throw forbidden('creator_inactive', 'You are not an active creator')
  return c
}

function nameOf(ctx: AppContext, uuid: string): string {
  return getUser(ctx, uuid)?.name ?? ''
}

function skinOf(ctx: AppContext, uuid: string): string | null {
  const u = getUser(ctx, uuid) as { skin_url?: string | null } | undefined
  return u?.skin_url && SKIN_URL_RE.test(u.skin_url) ? u.skin_url : null
}

function symbolRow(ctx: AppContext, id: number | null): SymbolRow | undefined {
  return id === null ? undefined : one<SymbolRow>(ctx.db, 'SELECT * FROM creator_symbols WHERE id = ?', id)
}

function capeRef(ctx: AppContext, c: Pick<CapeRow, 'id' | 'name' | 'sha256'>): FanCapeRef {
  return { capeId: c.id, name: c.name, url: capeUrl(ctx, c) }
}

/** Aktueller Fan-Umhang, den Fans gerade bekommen (nur Partner+ und freigegeben). */
function currentFanCape(ctx: AppContext, c: CreatorRow): CapeRow | undefined {
  if (!TIER_LIMITS[c.tier].fanCape || !c.fan_cape_id) return undefined
  const cape = getCape(ctx, c.fan_cape_id)
  return cape && cape.status === 'approved' && cape.creator_uuid === c.uuid ? cape : undefined
}

export function creatorCard(ctx: AppContext, c: CreatorRow): CreatorCard {
  const sym = symbolRow(ctx, c.symbol_id)
  const cape = currentFanCape(ctx, c)
  return {
    uuid: c.uuid,
    name: nameOf(ctx, c.uuid),
    skin: skinOf(ctx, c.uuid),
    tier: c.tier,
    channels: parseChannels(c.channels),
    symbol: sym && sym.status === 'approved' ? symbolView(sym) : null,
    fanCape: cape ? capeRef(ctx, cape) : null,
    milestone: c.milestone,
  }
}

// ---------------------------------------------------------------- Ereignisse

function notifyCreator(
  ctx: AppContext,
  uuid: string,
  kind: CreatorUpdateKind,
  extra: { reason?: string | null, tier?: CreatorTier, helper?: PlayerRef } = {},
): void {
  ctx.events.publish(uuid, {
    type: 'creator_updated',
    kind,
    ...(extra.reason ? { reason: extra.reason } : {}),
    ...(extra.tier ? { tier: extra.tier } : {}),
    ...(extra.helper ? { helper: extra.helper } : {}),
  }, { meOnly: true })
}

/** Fan-/Creator-Daten eines Spielers geändert → Beobachter schlagen ihn neu nach (§35.5). */
export function emitFan(ctx: AppContext, uuid: string): number {
  return emitToWatchers(ctx, uuid, () => ({ type: 'fan', uuid }))
}

/**
 * Symbol/Meilenstein/Status eines Creators geändert: einmal je Stream, der den Creator oder einen seiner aktuellen
 * Fans beobachtet, `fan_creator` schicken (die Mods schlagen alle Spieler mit diesem `fan.creator` neu nach).
 */
export function emitFanCreator(ctx: AppContext, creator: string): number {
  const streams = new Set<Watcher>(ctx.watch.watchersOf(creator))
  for (const r of all<{ fan_uuid: string }>(ctx.db, 'SELECT fan_uuid FROM creator_supports WHERE creator_uuid = ?', creator)) {
    if (!ctx.watch.isWatched(r.fan_uuid)) continue
    for (const w of ctx.watch.watchersOf(r.fan_uuid)) streams.add(w)
  }
  let sent = 0
  for (const w of streams) {
    try {
      w.send({ type: 'fan_creator', creator })
      sent++
    } catch {
      // Ein kaputter Stream darf die anderen nicht stören.
    }
  }
  return sent
}

function bumpDaily(ctx: AppContext, creator: string, field: 'new_fans' | 'redemptions'): void {
  const day = dayOf(ctx.now())
  if (field === 'new_fans') {
    run(ctx.db, `INSERT INTO creator_daily (creator_uuid, day, new_fans) VALUES (?, ?, 1)
                 ON CONFLICT(creator_uuid, day) DO UPDATE SET new_fans = new_fans + 1`, creator, day)
  } else {
    run(ctx.db, `INSERT INTO creator_daily (creator_uuid, day, redemptions) VALUES (?, ?, 1)
                 ON CONFLICT(creator_uuid, day) DO UPDATE SET redemptions = redemptions + 1`, creator, day)
  }
}

function activeFans(ctx: AppContext, creator: string): number {
  return one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM creator_supports WHERE creator_uuid = ?', creator)!.n
}

export function milestoneFor(fans: number): Milestone | null {
  let m: Milestone | null = null
  for (const x of MILESTONES) if (fans >= x.at) m = x.name
  return m
}

/**
 * Meilenstein prüfen (nach jeder neuen Unterstützung): nur nach oben. Neu erreicht → `creator_updated {milestone}` an den
 * Creator, `creator_milestone` an alle aktuellen Fans, `fan_creator` an die Mods (Rahmen um das Symbol).
 */
export function checkMilestone(ctx: AppContext, creator: string): Milestone | null {
  const c = creatorRow(ctx, creator)
  if (!c) return null
  const reached = milestoneFor(activeFans(ctx, creator))
  if (milestoneRank(reached) <= milestoneRank(c.milestone)) return null
  run(ctx.db, 'UPDATE creators SET milestone = ?, updated_at = ? WHERE uuid = ?', reached, ctx.now(), creator)
  notifyCreator(ctx, creator, 'milestone')
  const ref: PlayerRef = { uuid: creator, name: nameOf(ctx, creator) }
  for (const r of all<{ fan_uuid: string }>(ctx.db, 'SELECT fan_uuid FROM creator_supports WHERE creator_uuid = ?', creator)) {
    ctx.events.publish(r.fan_uuid, { type: 'creator_milestone', creator: ref, milestone: reached! }, { meOnly: true })
  }
  emitFanCreator(ctx, creator)
  return reached
}

// ---------------------------------------------------------------- Fans: einlösen, beenden, blockieren

function supportRow(ctx: AppContext, fan: string): SupportRow | undefined {
  return one<SupportRow>(ctx.db, 'SELECT * FROM creator_supports WHERE fan_uuid = ?', fan)
}

/** Ende der 30-Tage-Sperre für einen Wechsel zu einem ANDEREN Creator als dem zuletzt begonnenen (oder `null`). */
function switchLockUntil(ctx: AppContext, s: SupportRow | undefined): number | null {
  if (!s?.last_creator_uuid || s.last_started_at === null) return null
  const until = s.last_started_at + SWITCH_LOCK_MS
  if (until <= ctx.now()) return null
  // Entzogene/gesperrte Creator halten niemanden fest.
  return isActiveCreator(ctx, s.last_creator_uuid) ? until : null
}

export interface RedeemCreatorResult {
  creator: CreatorCard
  supportChanged: boolean
  switchAvailableAt: string | null
  fanCapeGranted: boolean
}

function notFoundCode(): ApiError {
  return notFound('creator_code_not_found', 'No creator with this code')
}

/**
 * Creator-Code einlösen (§35.1). Gleiche Antwort für unbekannt/inaktiv (kein Orakel), außer abgelaufene oder
 * aufgebrauchte Zusatz-Codes (410). Je Fan und Code zählt höchstens eine Einlösung.
 */
export function redeemCreatorCode(ctx: AppContext, fan: string, raw: unknown): RedeemCreatorResult {
  const input = parse(creatorCodeBody, raw)
  const code = normalizeCreatorCode(input.code)
  if (!code) throw notFoundCode()
  const k = one<CodeRow & { state: CreatorState, tier: CreatorTier }>(
    ctx.db,
    `SELECT k.*, c.state, c.tier FROM creator_codes k JOIN creators c ON c.uuid = k.creator_uuid
     WHERE k.code = ? AND k.creator_uuid NOT IN (${ACTIVE_BANS})`,
    code, ctx.now(),
  )
  if (!k || k.state !== 'active' || k.active !== 1) throw notFoundCode()
  const creator = k.creator_uuid
  if (creator === fan) throw conflict('creator_self', 'You cannot support yourself')
  if (one(ctx.db, 'SELECT 1 AS x FROM creator_blocks WHERE uuid = ? AND creator_uuid = ?', fan, creator)) {
    throw conflict('creator_blocked', 'You blocked this creator')
  }
  const t = ctx.now()
  const already = one(ctx.db, 'SELECT 1 AS x FROM creator_code_redemptions WHERE code_id = ? AND fan_uuid = ?', k.id, fan) !== undefined
  if (!already && k.kind === 'extra') {
    const cap = TIER_LIMITS[k.tier].maxUsesPerCode
    const max = k.max_uses === null ? cap : cap === null ? k.max_uses : Math.min(k.max_uses, cap)
    if ((k.expires_at !== null && k.expires_at <= t) || (max !== null && k.uses >= max)) {
      throw new ApiError(410, 'creator_code_expired', 'This code has expired or is used up')
    }
  }

  const r = tx(ctx.db, () => {
    let supportChanged = false
    let lockUntil: number | null = null
    let capeGranted = false
    if (!already) {
      run(ctx.db, 'INSERT INTO creator_code_redemptions (code_id, fan_uuid, redeemed_at) VALUES (?, ?, ?)', k.id, fan, t)
      run(ctx.db, 'UPDATE creator_codes SET uses = uses + 1 WHERE id = ?', k.id)
      bumpDaily(ctx, creator, 'redemptions')
    }
    const s = supportRow(ctx, fan)
    if (s?.creator_uuid !== creator) {
      lockUntil = s?.last_creator_uuid === creator ? null : switchLockUntil(ctx, s)
      if (lockUntil === null) {
        supportChanged = true
        // Gleicher Creator wie zuletzt begonnen → der ursprüngliche Start bleibt.
        const restart = s?.last_creator_uuid !== creator
        run(
          ctx.db,
          `INSERT INTO creator_supports (fan_uuid, creator_uuid, last_creator_uuid, last_started_at, updated_at) VALUES (?, ?, ?, ?, ?)
           ON CONFLICT(fan_uuid) DO UPDATE SET creator_uuid = excluded.creator_uuid,
             last_creator_uuid = excluded.last_creator_uuid, last_started_at = excluded.last_started_at, updated_at = excluded.updated_at`,
          fan, creator, creator, restart ? t : s!.last_started_at, t,
        )
        bumpDaily(ctx, creator, 'new_fans')
      }
    }
    // Fan-Umhang gibt es auch ohne Wechsel (z. B. während der 30 Tage).
    const c = creatorRow(ctx, creator)!
    const cape = currentFanCape(ctx, c)
    if (cape) {
      grantCape(ctx, fan, cape.id, 'creator')
      capeGranted = true
    }
    return { supportChanged, lockUntil, capeGranted }
  })
  const { supportChanged, lockUntil, capeGranted } = r
  if (supportChanged) {
    emitFan(ctx, fan)
    checkMilestone(ctx, creator)
  }
  const after = supportRow(ctx, fan)
  const until = supportChanged ? switchLockUntil(ctx, after) : lockUntil ?? switchLockUntil(ctx, after)
  return {
    creator: creatorCard(ctx, creatorRow(ctx, creator)!),
    supportChanged,
    switchAvailableAt: until === null ? null : iso(until),
    fanCapeGranted: capeGranted,
  }
}

/** Unterstützung beenden: Abzeichen weg, zählt nicht mehr als Fan. Fan-Umhänge bleiben. */
export function endSupport(ctx: AppContext, fan: string): void {
  const n = run(ctx.db, 'UPDATE creator_supports SET creator_uuid = NULL, updated_at = ? WHERE fan_uuid = ? AND creator_uuid IS NOT NULL', ctx.now(), fan)
  if (n > 0) emitFan(ctx, fan)
}

export function blockedList(ctx: AppContext, uuid: string): PlayerRef[] {
  return all<{ uuid: string, name: string | null }>(
    ctx.db,
    'SELECT b.creator_uuid AS uuid, u.name FROM creator_blocks b LEFT JOIN users u ON u.uuid = b.creator_uuid WHERE b.uuid = ? ORDER BY b.created_at',
    uuid,
  ).map((r) => ({ uuid: r.uuid, name: r.name ?? '' }))
}

/** Creator per Code blockieren (auch Haupt-Code). Den eigenen Creator kann man nicht blockieren. */
export function blockCreator(ctx: AppContext, uuid: string, raw: unknown): { blocked: PlayerRef[] } {
  const input = parse(creatorCodeBody, raw)
  const code = normalizeCreatorCode(input.code)
  const k = code ? one<{ creator_uuid: string }>(ctx.db, 'SELECT creator_uuid FROM creator_codes WHERE code = ?', code) : undefined
  if (!k) throw notFoundCode()
  if (k.creator_uuid === uuid) throw conflict('creator_self', 'You cannot block yourself')
  if (supportRow(ctx, uuid)?.creator_uuid === k.creator_uuid) throw conflict('creator_supported', 'You support this creator; end the support first')
  run(ctx.db, 'INSERT INTO creator_blocks (uuid, creator_uuid, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING', uuid, k.creator_uuid, ctx.now())
  return { blocked: blockedList(ctx, uuid) }
}

export function unblockCreator(ctx: AppContext, uuid: string, creator: string): { blocked: PlayerRef[] } {
  run(ctx.db, 'DELETE FROM creator_blocks WHERE uuid = ? AND creator_uuid = ?', uuid, creator)
  return { blocked: blockedList(ctx, uuid) }
}

export function setCreatorSettings(ctx: AppContext, uuid: string, raw: unknown): { showFanSymbols: boolean } {
  const input = parse(creatorSettingsBody, raw)
  run(ctx.db, 'UPDATE users SET show_fan_symbols = ? WHERE uuid = ?', input.showFanSymbols ? 1 : 0, uuid)
  return { showFanSymbols: input.showFanSymbols }
}

export interface MyCreatorState {
  support: { creator: CreatorCard | null, startedAt: string | null, lastCreatorUuid: string | null, switchAvailableAt: string | null }
  fanCapes: { capeId: string, name: string, creator: PlayerRef }[]
  blocked: PlayerRef[]
  showFanSymbols: boolean
  creator: CreatorSelf | null
  application: { id: string, status: string, createdAt: string } | null
  /** Creator, denen ich helfe (§35.9), und offene Einladungen. */
  helping: HelpingView[]
  helperInvites: HelperInviteView[]
}

/** `GET /v1/me/creator`: alles für die Creator-Seite im Launcher. */
export function myCreatorState(ctx: AppContext, uuid: string): MyCreatorState {
  const s = supportRow(ctx, uuid)
  const supported = s?.creator_uuid ? creatorRow(ctx, s.creator_uuid) : undefined
  const active = supported && supported.state === 'active' ? supported : undefined
  const lock = switchLockUntil(ctx, s)
  const fanCapes = all<{ id: string, name: string, creator_uuid: string, creator_name: string | null }>(
    ctx.db,
    `SELECT c.id, c.name, c.creator_uuid, u.name AS creator_name FROM user_capes g
     JOIN capes c ON c.id = g.cape_id LEFT JOIN users u ON u.uuid = c.creator_uuid
     WHERE g.uuid = ? AND c.creator_uuid IS NOT NULL AND c.status = 'approved' ORDER BY g.granted_at`,
    uuid,
  ).map((r) => ({ capeId: r.id, name: r.name, creator: { uuid: r.creator_uuid, name: r.creator_name ?? '' } }))
  const me = creatorRow(ctx, uuid)
  const app = one<{ id: string, status: string, created_at: number }>(
    ctx.db,
    `SELECT a.id, a.status, a.created_at FROM team_applications a JOIN team_jobs j ON j.id = a.job_id
     WHERE a.uuid = ? AND j.purpose = 'creator' ORDER BY a.created_at DESC LIMIT 1`,
    uuid,
  )
  return {
    support: {
      creator: active ? creatorCard(ctx, active) : null,
      startedAt: active && s?.last_started_at ? iso(s.last_started_at) : null,
      lastCreatorUuid: s?.last_creator_uuid ?? null,
      switchAvailableAt: lock === null ? null : iso(lock),
    },
    fanCapes,
    blocked: blockedList(ctx, uuid),
    showFanSymbols: (one<{ v: number }>(ctx.db, 'SELECT show_fan_symbols AS v FROM users WHERE uuid = ?', uuid)?.v ?? 1) === 1,
    creator: me ? creatorSelf(ctx, me) : null,
    application: app ? { id: app.id, status: app.status, createdAt: iso(app.created_at) } : null,
    ...myHelping(ctx, uuid),
  }
}

// ---------------------------------------------------------------- Creator-Sicht (Dashboard)

function codeView(k: CodeRow): CodeView {
  return {
    code: k.code,
    kind: k.kind,
    uses: k.uses,
    maxUses: k.max_uses,
    expiresAt: k.expires_at === null ? null : iso(k.expires_at),
    active: k.active === 1,
    createdAt: iso(k.created_at),
  }
}

function codesOf(ctx: AppContext, creator: string): CodeView[] {
  return all<CodeRow>(ctx.db, "SELECT * FROM creator_codes WHERE creator_uuid = ? ORDER BY kind = 'extra', created_at, id", creator).map(codeView)
}

function creatorStats(ctx: AppContext, creator: string): CreatorStats {
  const n = (sql: string, ...p: (string | number)[]) => one<{ n: number }>(ctx.db, sql, ...p)!.n
  const t = ctx.now()
  const today = Math.floor(t / DAY) * DAY
  const days = Array.from({ length: 30 }, (_, i) => dayOf(today - (29 - i) * DAY))
  const rows = new Map(all<{ day: string, new_fans: number, redemptions: number }>(
    ctx.db, 'SELECT day, new_fans, redemptions FROM creator_daily WHERE creator_uuid = ? AND day >= ?', creator, days[0]!,
  ).map((r) => [r.day, r]))
  return {
    activeFans: activeFans(ctx, creator),
    redemptions: n(
      'SELECT COUNT(*) AS n FROM creator_code_redemptions r JOIN creator_codes k ON k.id = r.code_id WHERE k.creator_uuid = ?', creator,
    ),
    fanCapeHolders: n(
      `SELECT COUNT(DISTINCT g.uuid) AS n FROM user_capes g JOIN capes c ON c.id = g.cape_id
       WHERE c.creator_uuid = ? AND g.uuid <> ?`, creator, creator,
    ),
    // Neue Spieler: TRS-Konto höchstens 24 h vor der ersten Einlösung eines Codes dieses Creators angelegt.
    newPlayers: n(
      `SELECT COUNT(*) AS n FROM (
         SELECT r.fan_uuid, MIN(r.redeemed_at) AS first FROM creator_code_redemptions r
         JOIN creator_codes k ON k.id = r.code_id WHERE k.creator_uuid = ? GROUP BY r.fan_uuid
       ) f JOIN users u ON u.uuid = f.fan_uuid WHERE f.first - u.created_at <= ?`, creator, DAY,
    ),
    packInstalls: n('SELECT COALESCE(SUM(installs), 0) AS n FROM shared_packs WHERE owner_uuid = ? AND creator_listed = 1', creator),
    daily: days.map((day) => ({ day, newFans: rows.get(day)?.new_fans ?? 0, redemptions: rows.get(day)?.redemptions ?? 0 })),
  }
}

function requestView(r: RequestRow): RequestView {
  return { id: r.id, kind: r.kind, status: r.status, createdAt: iso(r.created_at), reason: r.reason }
}

function ownPacks(ctx: AppContext, creator: string): CreatorPackRef[] {
  return all<PackRow>(
    ctx.db,
    'SELECT * FROM shared_packs WHERE owner_uuid = ? AND (expires_at IS NULL OR expires_at > ?) ORDER BY updated_at DESC, id',
    creator, ctx.now(),
  ).map((p) => ({ code: displayCode(p.code), name: p.name, installs: p.installs, listed: p.creator_listed === 1 }))
}

/** Letzte Ablehnung, wenn sie die jüngste Entscheidung ist (sonst `null`). */
function lastRejection(rows: { status: string, reason: string | null, reviewed_at: number | null }[]): { reason: string | null, at: string } | null {
  const decided = rows.filter((r) => r.reviewed_at !== null && r.status !== 'pending').sort((a, b) => b.reviewed_at! - a.reviewed_at!)[0]
  return decided && decided.status === 'rejected' ? { reason: decided.reason, at: iso(decided.reviewed_at!) } : null
}

export function creatorSelf(ctx: AppContext, c: CreatorRow, viewer?: CreatorActor): CreatorSelf {
  const helper = viewer?.helper === true
  const may = (r: HelperRight) => !helper || viewer!.rights.has(r)
  const current = symbolRow(ctx, c.symbol_id)
  const pending = one<SymbolRow>(ctx.db, "SELECT * FROM creator_symbols WHERE creator_uuid = ? AND status = 'pending' ORDER BY id DESC LIMIT 1", c.uuid)
  const symbolDecisions = all<SymbolRow>(
    ctx.db, "SELECT * FROM creator_symbols WHERE creator_uuid = ? AND status <> 'pending' ORDER BY reviewed_at DESC LIMIT 5", c.uuid,
  )
  const capes = all<CapeRow>(ctx.db, 'SELECT * FROM capes WHERE creator_uuid = ? ORDER BY created_at DESC LIMIT 20', c.uuid)
  const pendingCape = capes.find((x) => x.status === 'pending')
  const cape = currentFanCape(ctx, c)
  const next = MILESTONES.find((m) => milestoneRank(m.name) > milestoneRank(c.milestone)) ?? null
  return {
    uuid: c.uuid,
    name: nameOf(ctx, c.uuid),
    tier: c.tier,
    state: c.state,
    reason: c.reason,
    since: iso(c.created_at),
    channels: parseChannels(c.channels),
    codes: codesOf(ctx, c.uuid),
    limits: TIER_LIMITS[c.tier],
    stats: may('stats') ? creatorStats(ctx, c.uuid) : null,
    milestone: c.milestone,
    nextMilestone: next ? { name: next.name, at: next.at } : null,
    symbol: {
      current: current && current.status === 'approved' ? symbolView(current) : null,
      pending: pending ? symbolView(pending) : null,
      rejected: lastRejection(symbolDecisions),
    },
    fanCape: {
      current: cape ? capeRef(ctx, cape) : null,
      pending: pendingCape ? capeRef(ctx, pendingCape) : null,
      rejected: lastRejection(capes.map((x) => ({ status: x.status, reason: x.reject_reason, reviewed_at: x.reviewed_at }))),
    },
    requests: helper ? [] : all<RequestRow>(ctx.db, 'SELECT * FROM creator_requests WHERE creator_uuid = ? ORDER BY created_at DESC, id DESC LIMIT 20', c.uuid).map(requestView),
    packs: may('packs') ? ownPacks(ctx, c.uuid) : [],
    ...(helper ? { viewer: { helper: true as const, rights: HELPER_RIGHTS.filter((r) => viewer!.rights.has(r)) } } : { helpers: helpersOf(ctx, c.uuid) }),
  }
}

/** Dashboard (§35.3); mit `as` sieht ein Helfer die Sicht seines Creators (Statistik nur mit `stats`). */
export function creatorDashboard(ctx: AppContext, user: string, as?: string | null): CreatorSelf {
  const a = actorFor(ctx, user, as, null)
  return creatorSelf(ctx, a.creator, a)
}

// ---------------------------------------------------------------- Helfer (§35.9)

function parseRights(raw: string): HelperRight[] {
  try {
    const v = JSON.parse(raw) as unknown
    return Array.isArray(v) ? HELPER_RIGHTS.filter((r) => v.includes(r)) : []
  } catch {
    return []
  }
}

/**
 * Handelnder für eine Dashboard-Route. Ohne `as` (oder `as` = man selbst): der Creator, aktiv (sonst 403
 * `creator_inactive`). Mit `as`: nur aktive Helfer eines aktiven Creators mit dem Recht `need` (`owner` = nur der
 * Creator, `null` = irgendein Recht) – sonst 403 `creator_helper_right`.
 */
export function actorFor(ctx: AppContext, user: string, as: string | null | undefined, need: HelperRight | 'owner' | null): CreatorActor {
  if (!as || as === user) return { user, creator: requireActive(ctx, user), helper: false, rights: new Set(HELPER_RIGHTS) }
  const h = one<HelperRow>(ctx.db, "SELECT * FROM creator_helpers WHERE creator_uuid = ? AND helper_uuid = ? AND status = 'active'", as, user)
  if (!h) throw forbidden('creator_helper_right', 'You are not a helper of this creator')
  const creator = requireActive(ctx, as)
  const rights = new Set(parseRights(h.rights))
  if (need === 'owner' || (need !== null && !rights.has(need))) throw forbidden('creator_helper_right', 'You do not have this right as a helper')
  return { user, creator, helper: true, rights }
}

/** Aktionen eines Helfers landen mit seiner UUID im Audit-Log (Ziel = Creator). */
function auditHelper(ctx: AppContext, a: CreatorActor, action: string, detail: string): void {
  if (a.helper) audit(ctx, a.user, `creator.helper.${action}`, a.creator.uuid, detail)
}

/** Upload-Grenze je CREATOR (Helfer teilen sich den Topf mit ihm). */
function takeUpload(ctx: AppContext, kind: 'symbol' | 'cape', creator: string): void {
  const r = ctx.limiter.take(`creator-${kind}:${creator}`, RULES.creatorUploadUser)
  if (!r.ok) throw tooMany(r.retryAfter)
}

function helpersOf(ctx: AppContext, creator: string): HelperView[] {
  return all<HelperRow & { name: string | null, skin_url: string | null }>(
    ctx.db,
    `SELECT h.*, u.name, u.skin_url FROM creator_helpers h LEFT JOIN users u ON u.uuid = h.helper_uuid
     WHERE h.creator_uuid = ? ORDER BY h.created_at`,
    creator,
  ).map((h) => ({
    uuid: h.helper_uuid,
    name: h.name ?? '',
    skin: h.skin_url && SKIN_URL_RE.test(h.skin_url) ? h.skin_url : null,
    rights: parseRights(h.rights),
    status: h.status,
    createdAt: iso(h.created_at),
  }))
}

export const helperInviteBody = z.strictObject({
  uuid: z.string().max(36),
  rights: z.array(z.enum(HELPER_RIGHTS)).min(1).max(HELPER_RIGHTS.length),
})
export const helperRightsBody = z.strictObject({ rights: z.array(z.enum(HELPER_RIGHTS)).min(1).max(HELPER_RIGHTS.length) })
export const helperAnswerBody = z.strictObject({ accept: z.boolean() })

const rightsJson = (rights: readonly HelperRight[]) => JSON.stringify(HELPER_RIGHTS.filter((r) => rights.includes(r)))

/** Freund als Helfer einladen (nur der Creator). Grenze je Stufe: 1/3/10 (eingeladen + aktiv). */
export function inviteHelper(ctx: AppContext, user: string, raw: unknown): { helpers: HelperView[] } {
  const c = requireActive(ctx, user)
  const input = parse(helperInviteBody, raw)
  const target = normalizeUuid(input.uuid)
  if (!target || target === user) throw badRequest('invalid_request', 'Invite a friend by UUID')
  if (!getUser(ctx, target) || !areFriends(ctx, user, target)) throw badRequest('not_friends', 'Only TRS friends can be helpers')
  if (one(ctx.db, 'SELECT 1 AS x FROM creator_helpers WHERE creator_uuid = ? AND helper_uuid = ?', user, target)) {
    throw conflict('creator_helper_exists', 'This player is already a helper or invited')
  }
  const lim = HELPER_LIMITS[c.tier]
  if (one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM creator_helpers WHERE creator_uuid = ?', user)!.n >= lim) {
    throw forbidden('creator_helper_limit', `At most ${lim} helper(s) for your tier`)
  }
  const t = ctx.now()
  run(ctx.db, "INSERT INTO creator_helpers (creator_uuid, helper_uuid, rights, status, created_at) VALUES (?, ?, ?, 'pending', ?)", user, target, rightsJson(input.rights), t)
  ctx.events.publish(target, {
    type: 'creator_helper_invite',
    creator: { uuid: user, name: nameOf(ctx, user) },
    rights: HELPER_RIGHTS.filter((r) => input.rights.includes(r)),
    createdAt: iso(t),
  }, { meOnly: true })
  return { helpers: helpersOf(ctx, user) }
}

/** Rechte eines Helfers ändern (nur der Creator). */
export function updateHelper(ctx: AppContext, user: string, helper: string, raw: unknown): { helpers: HelperView[] } {
  requireActive(ctx, user)
  const input = parse(helperRightsBody, raw)
  if (run(ctx.db, 'UPDATE creator_helpers SET rights = ? WHERE creator_uuid = ? AND helper_uuid = ?', rightsJson(input.rights), user, helper) === 0) {
    throw notFound('helper_not_found', 'Helper not found')
  }
  return { helpers: helpersOf(ctx, user) }
}

/** Helfer entfernen bzw. Einladung zurückziehen (nur der Creator). Der Helfer bekommt `creator_helper_removed`. */
export function removeHelper(ctx: AppContext, user: string, helper: string): void {
  const c = creatorRow(ctx, user)
  if (!c) throw forbidden('creator_inactive', 'You are not an active creator')
  if (run(ctx.db, 'DELETE FROM creator_helpers WHERE creator_uuid = ? AND helper_uuid = ?', user, helper) === 0) throw notFound('helper_not_found', 'Helper not found')
  ctx.events.publish(helper, { type: 'creator_helper_removed', creator: { uuid: user, name: nameOf(ctx, user) } }, { meOnly: true })
}

export interface HelpingView {
  creator: { uuid: string, name: string, tier: CreatorTier }
  rights: HelperRight[]
}

export interface HelperInviteView {
  creator: PlayerRef
  rights: HelperRight[]
  createdAt: string
}

/** Wo ich helfe (aktiv, Creator aktiv) und offene Einladungen an mich. */
export function myHelping(ctx: AppContext, user: string): { helping: HelpingView[], helperInvites: HelperInviteView[] } {
  const rows = all<HelperRow & { name: string | null, tier: CreatorTier }>(
    ctx.db,
    `SELECT h.*, u.name, c.tier FROM creator_helpers h JOIN creators c ON c.uuid = h.creator_uuid AND c.state = 'active'
     LEFT JOIN users u ON u.uuid = h.creator_uuid WHERE h.helper_uuid = ? ORDER BY h.created_at`,
    user,
  )
  return {
    helping: rows.filter((r) => r.status === 'active').map((r) => ({ creator: { uuid: r.creator_uuid, name: r.name ?? '', tier: r.tier }, rights: parseRights(r.rights) })),
    helperInvites: rows.filter((r) => r.status === 'pending').map((r) => ({ creator: { uuid: r.creator_uuid, name: r.name ?? '' }, rights: parseRights(r.rights), createdAt: iso(r.created_at) })),
  }
}

/** Einladung annehmen oder ablehnen. Annehmen → `creator_updated {helper_joined}` an den Creator. */
export function answerHelperInvite(ctx: AppContext, user: string, creator: string, raw: unknown): { helping: HelpingView[], helperInvites: HelperInviteView[] } {
  const input = parse(helperAnswerBody, raw)
  const h = one<HelperRow>(ctx.db, "SELECT * FROM creator_helpers WHERE creator_uuid = ? AND helper_uuid = ? AND status = 'pending'", creator, user)
  if (!h || !isActiveCreator(ctx, creator)) throw notFound('helper_invite_not_found', 'Invitation not found')
  if (input.accept) {
    run(ctx.db, "UPDATE creator_helpers SET status = 'active', accepted_at = ? WHERE creator_uuid = ? AND helper_uuid = ?", ctx.now(), creator, user)
    notifyCreator(ctx, creator, 'helper_joined', { helper: { uuid: user, name: nameOf(ctx, user) } })
  } else {
    run(ctx.db, 'DELETE FROM creator_helpers WHERE creator_uuid = ? AND helper_uuid = ?', creator, user)
  }
  return myHelping(ctx, user)
}

/** Als Helfer aufhören → `creator_updated {helper_left}` an den Creator. */
export function leaveHelping(ctx: AppContext, user: string, creator: string): void {
  if (run(ctx.db, "DELETE FROM creator_helpers WHERE creator_uuid = ? AND helper_uuid = ? AND status = 'active'", creator, user) === 0) {
    throw notFound('helper_not_found', 'You are not a helper of this creator')
  }
  notifyCreator(ctx, creator, 'helper_left', { helper: { uuid: user, name: nameOf(ctx, user) } })
}

// ---------------------------------------------------------------- Codes

function activeExtras(ctx: AppContext, creator: string): number {
  return one<{ n: number }>(ctx.db, "SELECT COUNT(*) AS n FROM creator_codes WHERE creator_uuid = ? AND kind = 'extra' AND active = 1", creator)!.n
}

function checkExpiry(ctx: AppContext, at: string | null | undefined): number | null {
  if (at === undefined || at === null) return null
  const t = Date.parse(at)
  if (t <= ctx.now()) throw badRequest('invalid_request', 'expiresAt must be in the future')
  if (t > ctx.now() + 2 * 365 * DAY) throw badRequest('invalid_request', 'expiresAt must be within two years')
  return t
}

function checkMaxUses(c: CreatorRow, maxUses: number | null | undefined): number | null {
  const cap = TIER_LIMITS[c.tier].maxUsesPerCode
  if (maxUses === undefined || maxUses === null) return cap
  if (cap !== null && maxUses > cap) throw forbidden('creator_code_limit', `At most ${cap} uses per code for your tier`)
  return maxUses
}

/** Zusatz-Code anlegen (§35.3): Grenzen je Stufe, Code öffentlich und eindeutig. */
export function createExtraCode(ctx: AppContext, user: string, raw: unknown, as?: string | null): { codes: CodeView[] } {
  const a = actorFor(ctx, user, as, 'codes')
  const c = a.creator
  const uuid = c.uuid
  const input = parse(createCodeBody, raw)
  const code = normalizeCreatorCode(input.code)
  if (!code) throw badRequest('invalid_code', 'Codes have 5–20 characters: A–Z, 0–9 and dashes (not at the start or end)')
  const lim = TIER_LIMITS[c.tier].extraCodes
  if (lim !== null && activeExtras(ctx, uuid) >= lim) throw forbidden('creator_code_limit', `At most ${lim} extra code(s) for your tier`)
  const maxUses = checkMaxUses(c, input.maxUses)
  const expires = checkExpiry(ctx, input.expiresAt)
  if (codeTaken(ctx, code)) throw conflict('creator_code_taken', 'This code is already taken')
  run(
    ctx.db,
    "INSERT INTO creator_codes (creator_uuid, code, kind, uses, max_uses, expires_at, active, created_at) VALUES (?, ?, 'extra', 0, ?, ?, 1, ?)",
    uuid, code, maxUses, expires, ctx.now(),
  )
  auditHelper(ctx, a, 'code.create', code)
  return { codes: codesOf(ctx, uuid) }
}

/** Zusatz-Code ändern (an/aus, Grenze, Ablauf). Der Haupt-Code ist fest (ändert nur das Team). */
export function updateCode(ctx: AppContext, user: string, codeParam: string, raw: unknown, as?: string | null): { codes: CodeView[] } {
  const a = actorFor(ctx, user, as, 'codes')
  const c = a.creator
  const uuid = c.uuid
  const input = parse(updateCodeBody, raw)
  const code = normalizeCreatorCode(codeParam)
  const k = code ? one<CodeRow>(ctx.db, 'SELECT * FROM creator_codes WHERE code = ? AND creator_uuid = ?', code, uuid) : undefined
  if (!k) throw notFound('creator_code_not_found', 'Code not found')
  if (k.kind === 'main') throw badRequest('invalid_request', 'The main code cannot be changed (the team manages it)')
  let active = k.active
  if (input.active !== undefined) {
    if (input.active && k.active === 0) {
      const lim = TIER_LIMITS[c.tier].extraCodes
      if (lim !== null && activeExtras(ctx, uuid) >= lim) throw forbidden('creator_code_limit', `At most ${lim} extra code(s) for your tier`)
    }
    active = input.active ? 1 : 0
  }
  const maxUses = input.maxUses !== undefined ? checkMaxUses(c, input.maxUses) : k.max_uses
  const expires = input.expiresAt !== undefined ? checkExpiry(ctx, input.expiresAt) : k.expires_at
  run(ctx.db, 'UPDATE creator_codes SET active = ?, max_uses = ?, expires_at = ? WHERE id = ?', active, maxUses, expires, k.id)
  auditHelper(ctx, a, 'code.update', k.code)
  return { codes: codesOf(ctx, uuid) }
}

// ---------------------------------------------------------------- Fan-Symbol

function badSymbol(message: string, reason: string): ApiError {
  return badRequest('invalid_symbol', message, { reason })
}

export interface CleanSymbol {
  png: Buffer
  size: number
  frames: number
}

/**
 * Fan-Symbol prüfen und NEU kodieren: PNG (kein APNG, CRC, Chunk-Whitelist, Bomben-Schutz), quadratische Frames
 * 16–64 px senkrecht gestapelt (1–8), unsichtbare Pixel genullt, nicht ganz leer. `maxFrames` je Stufe.
 */
export function sanitizeSymbol(buf: Buffer, maxFrames: number): CleanSymbol {
  if (buf.length > SYMBOL_MAX_BYTES) throw new ApiError(413, 'payload_too_large', 'Symbol PNG must be at most 256 KiB')
  let header: { width: number, height: number }
  try {
    header = inspectPng(buf).header
  } catch (err) {
    throw badSymbol((err as Error).message, (err as { code?: string }).code ?? 'invalid_png')
  }
  const size = header.width
  if (size < SYMBOL_MIN_SIZE || size > SYMBOL_MAX_SIZE || header.height % size !== 0) {
    throw badSymbol('Each frame must be square, 16–64 px wide; frames are stacked vertically', 'invalid_dimensions')
  }
  const frames = header.height / size
  if (frames < 1 || frames > SYMBOL_MAX_FRAMES) throw badSymbol(`At most ${SYMBOL_MAX_FRAMES} frames`, 'too_many_frames')
  if (frames > maxFrames) throw forbidden('creator_symbol_frames', 'Animated symbols need the Partner tier')
  let img: { width: number, height: number, data: Buffer }
  try {
    img = decodeRgba(buf, SYMBOL_MAX_SIZE * SYMBOL_MAX_SIZE * SYMBOL_MAX_FRAMES)
  } catch (err) {
    throw badSymbol((err as Error).message, (err as { code?: string }).code ?? 'invalid_png')
  }
  const out = Buffer.from(img.data)
  let visible = 0
  for (let i = 0; i < out.length; i += 4) {
    if (out[i + 3] === 0) {
      out[i] = 0
      out[i + 1] = 0
      out[i + 2] = 0
    } else visible++
  }
  if (visible === 0) throw badSymbol('The symbol is fully transparent', 'empty')
  return { png: encodeRgba(img.width, img.height, out), size, frames }
}

/** Datei entfernen, wenn keine wartende/freigegebene Zeile sie mehr braucht. */
function dropSymbolFile(ctx: AppContext, sha: string): void {
  if (one(ctx.db, "SELECT 1 AS x FROM creator_symbols WHERE sha256 = ? AND status IN ('pending', 'approved')", sha)) return
  rmSync(join(symbolDir(ctx), `${sha}.png`), { force: true })
}

/** Neues Fan-Symbol (wartet auf Prüfung; ersetzt ein noch wartendes). Fans sehen bis zur Freigabe das alte. */
export function uploadSymbol(ctx: AppContext, user: string, body: Buffer, rawQuery: unknown, as?: string | null): { symbol: SymbolView } {
  const a = actorFor(ctx, user, as, 'uploads')
  const c = a.creator
  const uuid = c.uuid
  assertNotSanctioned(ctx, uuid, 'upload_ban')
  if (a.helper) assertNotSanctioned(ctx, user, 'upload_ban')
  const q = parse(symbolQuery, rawQuery)
  takeUpload(ctx, 'symbol', uuid)
  const clean = sanitizeSymbol(body, TIER_LIMITS[c.tier].symbolFrames)
  const sha = sha256Hex(clean.png)
  writeAtomic(symbolDir(ctx), `${sha}.png`, clean.png)
  const old = all<{ sha256: string }>(ctx.db, "SELECT sha256 FROM creator_symbols WHERE creator_uuid = ? AND status = 'pending'", uuid)
  const id = tx(ctx.db, () => {
    run(ctx.db, "DELETE FROM creator_symbols WHERE creator_uuid = ? AND status = 'pending'", uuid)
    run(
      ctx.db,
      "INSERT INTO creator_symbols (creator_uuid, sha256, size, frames, frame_ms, status, created_at) VALUES (?, ?, ?, ?, ?, 'pending', ?)",
      uuid, sha, clean.size, clean.frames, q.frameMs ?? SYMBOL_DEFAULT_FRAME_MS, ctx.now(),
    )
    return one<{ id: number }>(ctx.db, 'SELECT last_insert_rowid() AS id')!.id
  })
  for (const o of old) if (o.sha256 !== sha) dropSymbolFile(ctx, o.sha256)
  auditHelper(ctx, a, 'symbol.upload', sha)
  return { symbol: symbolView(symbolRow(ctx, id)!) }
}

export function deletePendingSymbol(ctx: AppContext, user: string, as?: string | null): void {
  const a = actorFor(ctx, user, as, 'uploads')
  const uuid = a.creator.uuid
  auditHelper(ctx, a, 'symbol.withdraw', '')
  const old = all<{ sha256: string }>(ctx.db, "SELECT sha256 FROM creator_symbols WHERE creator_uuid = ? AND status = 'pending'", uuid)
  run(ctx.db, "DELETE FROM creator_symbols WHERE creator_uuid = ? AND status = 'pending'", uuid)
  for (const o of old) dropSymbolFile(ctx, o.sha256)
}

/**
 * Symbol-Datei ausliefern: öffentlich nur, wenn eine freigegebene Zeile (aktuell oder früher) sie nutzt; sonst nur für
 * Prüfer (`viewer.admin`) und den Creator selbst (privat, nicht cachebar).
 */
export function readSymbol(ctx: AppContext, sha: string, viewer: { uuid: string, admin: boolean } | null): { png: Buffer, sha256: string, public: boolean } {
  const isPublic = one(ctx.db, "SELECT 1 AS x FROM creator_symbols WHERE sha256 = ? AND status = 'approved'", sha) !== undefined
  if (!isPublic) {
    const mine = viewer && one(ctx.db, 'SELECT 1 AS x FROM creator_symbols WHERE sha256 = ? AND creator_uuid = ?', sha, viewer.uuid) !== undefined
    if (!viewer || (!viewer.admin && !mine)) throw notFound('symbol_not_found', 'Symbol not found')
  }
  try {
    return { png: readFileSync(join(symbolDir(ctx), `${sha}.png`)), sha256: sha, public: isPublic }
  } catch {
    throw notFound('symbol_not_found', 'Symbol not found')
  }
}

// ---------------------------------------------------------------- Fan-Umhang

/** Neuer Fan-Umhang (Partner: Standard bis 512×256, 1 Frame; Top: HD bis 1024×512, bis 32 Frames). Ersetzt einen wartenden. */
export function uploadFanCape(ctx: AppContext, user: string, body: Buffer, rawQuery: unknown, as?: string | null): { fanCape: FanCapeRef } {
  const a = actorFor(ctx, user, as, 'uploads')
  const c = a.creator
  const uuid = c.uuid
  const kind = TIER_LIMITS[c.tier].fanCape
  if (!kind) throw forbidden('creator_tier', 'Fan capes need the Partner tier')
  assertNotSanctioned(ctx, uuid, 'upload_ban')
  if (a.helper) assertNotSanctioned(ctx, user, 'upload_ban')
  const q = parse(fanCapeQuery, rawQuery)
  takeUpload(ctx, 'cape', uuid)
  const clean = sanitizeCapeUpload(body, { frames: q.frames })
  if (kind === 'standard' && (clean.frames > 1 || clean.scale > STANDARD_CAPE_MAX_SCALE)) {
    throw forbidden('creator_tier', 'HD and animated fan capes need the Top tier (Partner: up to 512x256, one frame)')
  }
  if (clean.frames > 1 && q.frameTimeMs === undefined) {
    throw badRequest('frame_time_required', 'Animated capes need the frameTimeMs query parameter')
  }
  const old = all<{ id: string }>(ctx.db, "SELECT id FROM capes WHERE creator_uuid = ? AND status = 'pending'", uuid)
  for (const o of old) removeCape(ctx, o.id)
  const id = newUploadCapeId()
  const sha = sha256Hex(clean.png)
  writeAtomic(ctx.capeDir, `${id}.png`, clean.png)
  const name = q.name ?? `Fan-Umhang ${nameOf(ctx, uuid)}`.slice(0, 32).trim()
  try {
    run(
      ctx.db,
      `INSERT INTO capes (id, kind, name, owner_uuid, status, unlock, sha256, width, height, frames, frame_time_ms, sort, retired, created_at, creator_uuid)
       VALUES (?, 'upload', ?, ?, 'pending', 'code', ?, ?, ?, ?, ?, 0, 0, ?, ?)`,
      id, name, uuid, sha, clean.width, clean.height, clean.frames, clean.frames > 1 ? q.frameTimeMs! : null, ctx.now(), uuid,
    )
  } catch (err) {
    rmSync(join(ctx.capeDir, `${id}.png`), { force: true })
    throw err
  }
  auditHelper(ctx, a, 'cape.upload', id)
  return { fanCape: capeRef(ctx, getCape(ctx, id)!) }
}

export function deletePendingFanCape(ctx: AppContext, user: string, as?: string | null): void {
  const a = actorFor(ctx, user, as, 'uploads')
  const uuid = a.creator.uuid
  auditHelper(ctx, a, 'cape.withdraw', '')
  for (const o of all<{ id: string }>(ctx.db, "SELECT id FROM capes WHERE creator_uuid = ? AND status = 'pending'", uuid)) removeCape(ctx, o.id)
}

// ---------------------------------------------------------------- Anfragen, Vorschau-Builds, Packs (Creator)

export function createRequest(ctx: AppContext, uuid: string, raw: unknown, as?: string | null): { requests: RequestView[] } {
  const c = actorFor(ctx, uuid, as, 'owner').creator
  const input = parse(requestBody, raw)
  if (input.kind === 'upgrade' && c.tier === 'top') throw forbidden('creator_tier', 'You already have the highest tier')
  if (one(ctx.db, "SELECT 1 AS x FROM creator_requests WHERE creator_uuid = ? AND kind = ? AND status = 'open'", uuid, input.kind)) {
    throw conflict('creator_request_open', 'You already have an open request of this kind')
  }
  const { kind, ...data } = input
  run(ctx.db, 'INSERT INTO creator_requests (creator_uuid, kind, status, data, created_at) VALUES (?, ?, \'open\', ?, ?)', uuid, kind, JSON.stringify(data), ctx.now())
  return { requests: all<RequestRow>(ctx.db, 'SELECT * FROM creator_requests WHERE creator_uuid = ? ORDER BY created_at DESC, id DESC LIMIT 20', uuid).map(requestView) }
}

function previewView(r: PreviewRow): PreviewView {
  return { id: r.id, product: r.product, version: r.version, notes: r.notes, url: r.url, sha256: r.sha256, publishedAt: iso(r.published_at) }
}

function allPreviews(ctx: AppContext): PreviewView[] {
  return all<PreviewRow>(ctx.db, 'SELECT * FROM creator_previews ORDER BY published_at DESC, id DESC LIMIT 100').map(previewView)
}

/** Vorschau-Builds (Partner+). */
export function creatorPreviews(ctx: AppContext, uuid: string, as?: string | null): { previews: PreviewView[] } {
  const c = actorFor(ctx, uuid, as, 'owner').creator
  if (c.tier === 'starter') throw forbidden('creator_tier', 'Preview builds need the Partner tier')
  return { previews: allPreviews(ctx) }
}

/** Eigenes geteiltes Pack (§27) als Creator-Pack listen oder entfernen. */
export function setCreatorPackListed(ctx: AppContext, user: string, packCode: string, raw: unknown, as?: string | null): { packs: CreatorPackRef[] } {
  const a = actorFor(ctx, user, as, 'packs')
  // Nur Packs des CREATORS – Helfer können ihre eigenen Packs nicht als Creator-Packs listen.
  const uuid = a.creator.uuid
  const input = parse(packListedBody, raw)
  const code = normalizePackCode(packCode)
  const p = code
    ? one<PackRow>(ctx.db, 'SELECT * FROM shared_packs WHERE code = ? AND (expires_at IS NULL OR expires_at > ?)', code, ctx.now())
    : undefined
  if (!p) throw notFound('pack_not_found', 'Modpack not found')
  if (p.owner_uuid !== uuid) throw forbidden('not_your_pack', 'Only your own modpacks can be creator packs')
  setPackCreatorListed(ctx, p, input.listed)
  auditHelper(ctx, a, input.listed ? 'pack.list' : 'pack.unlist', p.code)
  return { packs: ownPacks(ctx, uuid) }
}

export interface PublicCreatorPack {
  code: string
  name: string
  summary?: string
  mcVersion: string
  loader: string
  installs: number
  creator: { uuid: string, name: string, tier: CreatorTier }
}

/** Öffentliche Liste der Creator-Packs (gelistet, Creator aktiv), meiste Installationen zuerst. Blockierte fehlen. */
export function listCreatorPacks(ctx: AppContext, viewer: string | null, rawQuery: unknown): { packs: PublicCreatorPack[], total: number } {
  const q = parse(creatorPacksQuery, rawQuery)
  const t = ctx.now()
  const where = [
    'p.creator_listed = 1',
    "c.state = 'active'",
    '(p.expires_at IS NULL OR p.expires_at > ?)',
    `p.owner_uuid NOT IN (${ACTIVE_BANS})`,
  ]
  const params: (string | number)[] = [t, t]
  if (viewer) {
    where.push('p.owner_uuid NOT IN (SELECT creator_uuid FROM creator_blocks WHERE uuid = ?)')
    params.push(viewer)
  }
  if (q.q) {
    // Teilstring ohne LIKE-Platzhalter aus der Eingabe.
    where.push('(instr(lower(p.name), lower(?)) > 0 OR instr(lower(u.name), lower(?)) > 0)')
    params.push(q.q, q.q)
  }
  const from = `FROM shared_packs p JOIN creators c ON c.uuid = p.owner_uuid JOIN users u ON u.uuid = p.owner_uuid WHERE ${where.join(' AND ')}`
  const total = one<{ n: number }>(ctx.db, `SELECT COUNT(*) AS n ${from}`, ...params)!.n
  const rows = all<PackRow & { creator_name: string, tier: CreatorTier }>(
    ctx.db,
    `SELECT p.*, u.name AS creator_name, c.tier ${from} ORDER BY p.installs DESC, p.updated_at DESC, p.id LIMIT ? OFFSET ?`,
    ...params, q.limit, q.offset,
  )
  return {
    packs: rows.map((p) => ({
      code: displayCode(p.code),
      name: p.name,
      ...(p.summary ? { summary: p.summary } : {}),
      mcVersion: p.mc_version,
      loader: p.loader,
      installs: p.installs,
      creator: { uuid: p.owner_uuid, name: p.creator_name, tier: p.tier },
    })),
    total,
  }
}

// ---------------------------------------------------------------- Team: Prüfung

export interface QueueItem {
  id: string
  kind: 'symbol' | 'fan_cape'
  creator: { uuid: string, name: string, tier: CreatorTier }
  symbol?: SymbolView
  cape?: { capeId: string, url: string, name: string, frames: number, scale: number, frameTimeMs: number | null }
  createdAt: string
}

export function reviewQueue(ctx: AppContext): { items: QueueItem[] } {
  const symbols = all<SymbolRow & { name: string | null, tier: CreatorTier }>(
    ctx.db,
    `SELECT y.*, u.name, c.tier FROM creator_symbols y JOIN creators c ON c.uuid = y.creator_uuid LEFT JOIN users u ON u.uuid = y.creator_uuid
     WHERE y.status = 'pending' ORDER BY y.created_at LIMIT 200`,
  )
  const capes = all<CapeRow & { cname: string | null, tier: CreatorTier }>(
    ctx.db,
    `SELECT k.*, u.name AS cname, c.tier FROM capes k JOIN creators c ON c.uuid = k.creator_uuid LEFT JOIN users u ON u.uuid = k.creator_uuid
     WHERE k.creator_uuid IS NOT NULL AND k.status = 'pending' ORDER BY k.created_at LIMIT 200`,
  )
  const items: (QueueItem & { at: number })[] = [
    ...symbols.map((s) => ({
      id: `s${s.id}`,
      kind: 'symbol' as const,
      creator: { uuid: s.creator_uuid, name: s.name ?? '', tier: s.tier },
      symbol: symbolView(s),
      createdAt: iso(s.created_at),
      at: s.created_at,
    })),
    ...capes.map((k) => ({
      id: `c${k.id}`,
      kind: 'fan_cape' as const,
      creator: { uuid: k.creator_uuid!, name: k.cname ?? '', tier: k.tier },
      cape: {
        capeId: k.id,
        url: capeUrl(ctx, k),
        name: k.name,
        frames: k.frames,
        scale: Math.round(k.width / 64),
        frameTimeMs: k.frames > 1 ? k.frame_time_ms : null,
      },
      createdAt: iso(k.created_at),
      at: k.created_at,
    })),
  ]
  items.sort((a, b) => a.at - b.at)
  return { items: items.map(({ at: _at, ...rest }) => rest) }
}

/** Prüf-Eintrag annehmen oder ablehnen (`reason` Pflicht beim Ablehnen). Schickt `creator_updated` an den Creator. */
export function decideQueueItem(ctx: AppContext, staff: Staff, id: string, approve: boolean, raw?: unknown): void {
  assertCan(staff, 'creators.review')
  if (!QUEUE_ID.test(id)) throw notFound('queue_item_not_found', 'Not found')
  const reason = approve ? null : parse(rejectBody, raw).reason
  const t = ctx.now()
  if (id.startsWith('s')) {
    const s = one<SymbolRow>(ctx.db, "SELECT * FROM creator_symbols WHERE id = ? AND status = 'pending'", Number(id.slice(1)))
    if (!s) throw notFound('queue_item_not_found', 'Not found (already decided?)')
    tx(ctx.db, () => {
      run(
        ctx.db,
        'UPDATE creator_symbols SET status = ?, reason = ?, reviewed_at = ?, reviewed_by = ? WHERE id = ?',
        approve ? 'approved' : 'rejected', reason, t, staff.uuid, s.id,
      )
      if (approve) run(ctx.db, 'UPDATE creators SET symbol_id = ?, updated_at = ? WHERE uuid = ?', s.id, t, s.creator_uuid)
      audit(ctx, staff.uuid, approve ? 'creator.symbol.approve' : 'creator.symbol.reject', s.creator_uuid, approve ? `s${s.id}` : `s${s.id}: ${reason}`)
    })
    if (!approve) dropSymbolFile(ctx, s.sha256)
    notifyCreator(ctx, s.creator_uuid, approve ? 'symbol_approved' : 'symbol_rejected', { reason })
    if (approve) emitFanCreator(ctx, s.creator_uuid)
    return
  }
  const capeId = id.slice(1)
  const k = getCape(ctx, capeId)
  if (!k || !k.creator_uuid || k.status !== 'pending') throw notFound('queue_item_not_found', 'Not found (already decided?)')
  const creator = k.creator_uuid
  const worn = capeWearers(ctx, k.id)
  tx(ctx.db, () => {
    run(
      ctx.db,
      'UPDATE capes SET status = ?, reviewed_at = ?, reviewed_by = ?, reject_reason = ? WHERE id = ?',
      approve ? 'approved' : 'rejected', t, staff.uuid, reason, k.id,
    )
    if (approve) {
      run(ctx.db, 'UPDATE creators SET fan_cape_id = ?, updated_at = ? WHERE uuid = ?', k.id, t, creator)
      // Der Creator selbst bekommt seinen Fan-Umhang auch.
      grantCape(ctx, creator, k.id, 'creator')
    } else {
      run(ctx.db, 'UPDATE users SET active_cape_id = NULL WHERE active_cape_id = ?', k.id)
    }
    audit(ctx, staff.uuid, approve ? 'creator.cape.approve' : 'creator.cape.reject', creator, approve ? k.id : `${k.id}: ${reason}`)
  })
  for (const u of worn) emitCape(ctx, u)
  notifyCreator(ctx, creator, approve ? 'cape_approved' : 'cape_rejected', { reason })
}

// ---------------------------------------------------------------- Team: Creator verwalten

export interface AdminCreatorView {
  uuid: string
  name: string
  tier: CreatorTier
  state: CreatorState
  reason: string | null
  fans: number
  mainCode: string | null
  since: string
  channels: Channel[]
  symbol: SymbolView | null
  fanCape: FanCapeRef | null
  milestone: Milestone | null
  /** Helfer (nur lesend im Team-Bereich). */
  helpers: HelperView[]
}

function adminView(ctx: AppContext, c: CreatorRow): AdminCreatorView {
  const sym = symbolRow(ctx, c.symbol_id)
  const cape = currentFanCape(ctx, c)
  return {
    uuid: c.uuid,
    name: nameOf(ctx, c.uuid),
    tier: c.tier,
    state: c.state,
    reason: c.reason,
    fans: activeFans(ctx, c.uuid),
    mainCode: one<{ code: string }>(ctx.db, "SELECT code FROM creator_codes WHERE creator_uuid = ? AND kind = 'main'", c.uuid)?.code ?? null,
    since: iso(c.created_at),
    channels: parseChannels(c.channels),
    symbol: sym && sym.status === 'approved' ? symbolView(sym) : null,
    fanCape: cape ? capeRef(ctx, cape) : null,
    milestone: c.milestone,
    helpers: helpersOf(ctx, c.uuid),
  }
}

export function adminListCreators(ctx: AppContext, rawQuery: unknown): { creators: AdminCreatorView[] } {
  const q = parse(adminListQuery, rawQuery)
  const where: string[] = []
  const params: string[] = []
  if (q.state) {
    where.push('c.state = ?')
    params.push(q.state)
  }
  if (q.q) {
    where.push(`(instr(u.name_lower, lower(?)) > 0 OR c.uuid = ? OR EXISTS (SELECT 1 FROM creator_codes k WHERE k.creator_uuid = c.uuid AND instr(k.code, upper(?)) > 0))`)
    params.push(q.q, normalizeUuid(q.q) ?? '', q.q)
  }
  const rows = all<CreatorRow>(
    ctx.db,
    `SELECT c.* FROM creators c LEFT JOIN users u ON u.uuid = c.uuid ${where.length ? `WHERE ${where.join(' AND ')}` : ''}
     ORDER BY c.state = 'active' DESC, c.created_at DESC LIMIT 200`,
    ...params,
  )
  return { creators: rows.map((c) => adminView(ctx, c)) }
}

/**
 * Creator anlegen OHNE eigene Transaktion (auch aus der Bewerbung heraus). Liefert die Nacharbeit (Ereignisse) für nach
 * dem Commit. `mainCode` (optional) muss frei sein; sonst Standard-Code mit angehängter Zahl.
 */
export function insertCreatorInTx(ctx: AppContext, uuid: string, tier: CreatorTier, mainCode?: string): () => void {
  const u = getUser(ctx, uuid)
  if (!u) throw notFound('player_not_found', 'This player has never used TRS')
  if (creatorRow(ctx, uuid)) throw conflict('already_creator', 'This player is already a creator')
  let code: string
  if (mainCode !== undefined) {
    const n = normalizeCreatorCode(mainCode)
    if (!n) throw badRequest('invalid_code', 'Codes have 5–20 characters: A–Z, 0–9 and dashes (not at the start or end)')
    if (codeTaken(ctx, n)) throw conflict('creator_code_taken', 'This code is already taken')
    code = n
  } else {
    code = freeCode(ctx, defaultMainCode(u.name, uuid))
  }
  const t = ctx.now()
  run(ctx.db, "INSERT INTO creators (uuid, tier, state, channels, created_at, updated_at) VALUES (?, ?, 'active', '[]', ?, ?)", uuid, tier, t, t)
  run(ctx.db, "INSERT INTO creator_codes (creator_uuid, code, kind, active, created_at) VALUES (?, ?, 'main', 1, ?)", uuid, code, t)
  return () => {
    notifyCreator(ctx, uuid, 'became_creator', { tier })
    emitFan(ctx, uuid)
  }
}

function resolvePlayer(ctx: AppContext, input: string): string {
  const uuid = normalizeUuid(input)
  const u = uuid ? getUser(ctx, uuid) : /^[A-Za-z0-9_]{1,16}$/.test(input) ? getUserByName(ctx, input) : undefined
  if (!u) throw notFound('player_not_found', 'No TRS player with this name or UUID')
  return u.uuid
}

export function adminCreateCreator(ctx: AppContext, staff: Staff, raw: unknown): { creator: AdminCreatorView } {
  assertCan(staff, 'creators.manage')
  const input = parse(adminCreateBody, raw)
  const uuid = resolvePlayer(ctx, input.player)
  const after = tx(ctx.db, () => {
    const f = insertCreatorInTx(ctx, uuid, input.tier, input.mainCode)
    audit(ctx, staff.uuid, 'creator.create', uuid, input.tier)
    return f
  })
  after()
  return { creator: adminView(ctx, creatorRow(ctx, uuid)!) }
}

function adminRow(ctx: AppContext, uuid: string): CreatorRow {
  const c = creatorRow(ctx, uuid)
  if (!c) throw notFound('creator_not_found', 'Creator not found')
  return c
}

export function adminUpdateCreator(ctx: AppContext, staff: Staff, uuid: string, raw: unknown): { creator: AdminCreatorView } {
  assertCan(staff, 'creators.manage')
  const input = parse(adminUpdateBody, raw)
  const c = adminRow(ctx, uuid)
  const t = ctx.now()
  let code: string | null = null
  if (input.mainCode !== undefined) {
    code = normalizeCreatorCode(input.mainCode)
    if (!code) throw badRequest('invalid_code', 'Codes have 5–20 characters: A–Z, 0–9 and dashes (not at the start or end)')
    const owner = one<{ creator_uuid: string, kind: string }>(ctx.db, 'SELECT creator_uuid, kind FROM creator_codes WHERE code = ?', code)
    if (owner && !(owner.creator_uuid === uuid && owner.kind === 'main')) throw conflict('creator_code_taken', 'This code is already taken')
  }
  const tierChanged = input.tier !== undefined && input.tier !== c.tier
  tx(ctx.db, () => {
    if (tierChanged) run(ctx.db, 'UPDATE creators SET tier = ?, updated_at = ? WHERE uuid = ?', input.tier!, t, uuid)
    if (input.channels !== undefined) run(ctx.db, 'UPDATE creators SET channels = ?, updated_at = ? WHERE uuid = ?', JSON.stringify(input.channels), t, uuid)
    if (code !== null) run(ctx.db, "UPDATE creator_codes SET code = ? WHERE creator_uuid = ? AND kind = 'main'", code, uuid)
    const parts = [
      tierChanged ? `tier ${c.tier} → ${input.tier}` : null,
      code !== null ? `main code ${code}` : null,
      input.channels !== undefined ? `channels (${input.channels.length})` : null,
    ].filter(Boolean)
    audit(ctx, staff.uuid, 'creator.update', uuid, parts.join(', '))
  })
  if (tierChanged) {
    notifyCreator(ctx, uuid, 'tier_changed', { tier: input.tier })
    emitFan(ctx, uuid)
    emitFanCreator(ctx, uuid)
  }
  return { creator: adminView(ctx, creatorRow(ctx, uuid)!) }
}

/** Creator-Codes aus der Liste nehmen (Creator-Packs laufen dann wieder ab). */
function unlistPacks(ctx: AppContext, uuid: string): void {
  for (const p of all<PackRow>(ctx.db, 'SELECT * FROM shared_packs WHERE owner_uuid = ? AND creator_listed = 1', uuid)) setPackCreatorListed(ctx, p, false)
}

/**
 * Status entziehen (`ban: false`) oder sperren (`ban: true`, keine neue Bewerbung): Codes, Symbol, Abzeichen und
 * Creator-Packs sind aus; Fans behalten ihre Zeile (versteckt). `removeFanCapes` nimmt allen die Fan-Umhänge weg.
 */
export function adminRevokeCreator(ctx: AppContext, staff: Staff, uuid: string, raw: unknown): { creator: AdminCreatorView } {
  assertCan(staff, 'creators.manage')
  const input = parse(revokeBody, raw)
  adminRow(ctx, uuid)
  const t = ctx.now()
  const state: CreatorState = input.ban ? 'banned' : 'revoked'
  const fanCapeIds = all<{ id: string }>(ctx.db, 'SELECT id FROM capes WHERE creator_uuid = ?', uuid).map((r) => r.id)
  const worn = input.removeFanCapes && fanCapeIds.length
    ? all<{ uuid: string }>(ctx.db, `SELECT uuid FROM users WHERE active_cape_id IN (${placeholders(fanCapeIds.length)})`, ...fanCapeIds).map((r) => r.uuid)
    : []
  tx(ctx.db, () => {
    run(ctx.db, 'UPDATE creators SET state = ?, reason = ?, updated_at = ? WHERE uuid = ?', state, input.reason, t, uuid)
    if (input.removeFanCapes && fanCapeIds.length) {
      run(ctx.db, `DELETE FROM user_capes WHERE cape_id IN (${placeholders(fanCapeIds.length)})`, ...fanCapeIds)
      run(ctx.db, `UPDATE users SET active_cape_id = NULL WHERE active_cape_id IN (${placeholders(fanCapeIds.length)})`, ...fanCapeIds)
    }
    run(ctx.db, "UPDATE creator_requests SET status = 'declined', decided_at = ?, decided_by = ? WHERE creator_uuid = ? AND status = 'open'", t, staff.uuid, uuid)
    unlistPacks(ctx, uuid)
    audit(ctx, staff.uuid, input.ban ? 'creator.ban' : 'creator.revoke', uuid, `${input.reason}${input.removeFanCapes ? ' (+ fan capes removed)' : ''}`)
  })
  for (const u of worn) emitCape(ctx, u)
  notifyCreator(ctx, uuid, 'revoked', { reason: input.reason })
  emitFan(ctx, uuid)
  emitFanCreator(ctx, uuid)
  return { creator: adminView(ctx, creatorRow(ctx, uuid)!) }
}

export function adminRestoreCreator(ctx: AppContext, staff: Staff, uuid: string): { creator: AdminCreatorView } {
  assertCan(staff, 'creators.manage')
  const c = adminRow(ctx, uuid)
  if (c.state === 'active') return { creator: adminView(ctx, c) }
  run(ctx.db, "UPDATE creators SET state = 'active', reason = NULL, updated_at = ? WHERE uuid = ?", ctx.now(), uuid)
  audit(ctx, staff.uuid, 'creator.restore', uuid, c.state)
  notifyCreator(ctx, uuid, 'restored')
  emitFan(ctx, uuid)
  emitFanCreator(ctx, uuid)
  return { creator: adminView(ctx, creatorRow(ctx, uuid)!) }
}

// ---------------------------------------------------------------- Team: Anfragen

export interface AdminRequestView extends RequestView {
  creator: { uuid: string, name: string, tier: CreatorTier }
  data: Record<string, unknown>
  decidedAt: string | null
}

export function adminListRequests(ctx: AppContext, rawQuery: unknown): { requests: AdminRequestView[] } {
  const q = parse(requestListQuery, rawQuery)
  const rows = all<RequestRow & { name: string | null, tier: CreatorTier }>(
    ctx.db,
    `SELECT r.*, u.name, c.tier FROM creator_requests r JOIN creators c ON c.uuid = r.creator_uuid LEFT JOIN users u ON u.uuid = r.creator_uuid
     ${q.status ? 'WHERE r.status = ?' : ''} ORDER BY r.status = 'open' DESC, r.created_at DESC LIMIT 200`,
    ...(q.status ? [q.status] : []),
  )
  return {
    requests: rows.map((r) => {
      let data: Record<string, unknown>
      try {
        data = JSON.parse(r.data) as Record<string, unknown>
      } catch {
        data = {}
      }
      return {
        ...requestView(r),
        creator: { uuid: r.creator_uuid, name: r.name ?? '', tier: r.tier },
        data,
        decidedAt: r.decided_at === null ? null : iso(r.decided_at),
      }
    }),
  }
}

const NEXT_TIER: Record<CreatorTier, CreatorTier> = { starter: 'partner', partner: 'top', top: 'top' }

/** Anfrage beantworten. Angenommener Aufstieg hebt die Stufe um eins. */
export function adminDecideRequest(ctx: AppContext, staff: Staff, id: number, raw: unknown): { requests: AdminRequestView[] } {
  assertCan(staff, 'creators.manage')
  const input = parse(requestDecisionBody, raw)
  const r = one<RequestRow>(ctx.db, 'SELECT * FROM creator_requests WHERE id = ?', id)
  if (!r) throw notFound('request_not_found', 'Request not found')
  if (r.status !== 'open') throw conflict('request_closed', 'This request was already answered')
  const c = adminRow(ctx, r.creator_uuid)
  const t = ctx.now()
  const raise = input.status === 'accepted' && r.kind === 'upgrade' && c.tier !== 'top'
  tx(ctx.db, () => {
    run(ctx.db, 'UPDATE creator_requests SET status = ?, reason = ?, decided_at = ?, decided_by = ? WHERE id = ?', input.status, input.reason ?? null, t, staff.uuid, id)
    if (raise) run(ctx.db, 'UPDATE creators SET tier = ?, updated_at = ? WHERE uuid = ?', NEXT_TIER[c.tier], t, c.uuid)
    audit(ctx, staff.uuid, `creator.request.${input.status}`, c.uuid, `${r.kind} #${id}${raise ? ` → ${NEXT_TIER[c.tier]}` : ''}`)
  })
  notifyCreator(ctx, c.uuid, 'request_answered', { reason: input.reason })
  if (raise) {
    notifyCreator(ctx, c.uuid, 'tier_changed', { tier: NEXT_TIER[c.tier] })
    emitFan(ctx, c.uuid)
    emitFanCreator(ctx, c.uuid)
  }
  return adminListRequests(ctx, {})
}

// ---------------------------------------------------------------- Team: Vorschau-Builds

export function adminListPreviews(ctx: AppContext, staff: Staff): { previews: PreviewView[] } {
  assertCan(staff, 'creators.manage')
  return { previews: allPreviews(ctx) }
}

export function adminCreatePreview(ctx: AppContext, staff: Staff, raw: unknown): { preview: PreviewView } {
  assertCan(staff, 'creators.manage')
  const input = parse(previewBody, raw)
  const at = input.publishedAt ? Date.parse(input.publishedAt) : ctx.now()
  const id = tx(ctx.db, () => {
    run(
      ctx.db,
      'INSERT INTO creator_previews (product, version, notes, url, sha256, published_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)',
      input.product, input.version, input.notes, input.url, input.sha256, at, staff.uuid,
    )
    const n = one<{ id: number }>(ctx.db, 'SELECT last_insert_rowid() AS id')!.id
    audit(ctx, staff.uuid, 'creator.preview.create', null, `${input.product} ${input.version}`)
    return n
  })
  return { preview: previewView(one<PreviewRow>(ctx.db, 'SELECT * FROM creator_previews WHERE id = ?', id)!) }
}

export function adminDeletePreview(ctx: AppContext, staff: Staff, id: number): void {
  assertCan(staff, 'creators.manage')
  const r = one<PreviewRow>(ctx.db, 'SELECT * FROM creator_previews WHERE id = ?', id)
  if (!r) throw notFound('preview_not_found', 'Preview not found')
  run(ctx.db, 'DELETE FROM creator_previews WHERE id = ?', id)
  audit(ctx, staff.uuid, 'creator.preview.delete', null, `${r.product} ${r.version}`)
}

// ---------------------------------------------------------------- Bewerbungen (§24.3)

/**
 * Angenommene Bewerbung auf eine Creator-Stelle (OHNE eigene Transaktion): neuer Creator (Starter, Standard-Code) bzw.
 * ein entzogener wird wieder aktiv (als Starter). Gesperrte → 409 `creator_banned`. Liefert die Nacharbeit.
 */
export function creatorFromApplicationInTx(ctx: AppContext, uuid: string): () => void {
  const c = creatorRow(ctx, uuid)
  if (!c) return insertCreatorInTx(ctx, uuid, 'starter')
  if (c.state === 'banned') throw conflict('creator_banned', 'This player is banned from the creator programme')
  if (c.state === 'active') return () => {}
  run(ctx.db, "UPDATE creators SET state = 'active', tier = 'starter', reason = NULL, updated_at = ? WHERE uuid = ?", ctx.now(), uuid)
  return () => {
    notifyCreator(ctx, uuid, 'became_creator', { tier: 'starter' })
    emitFan(ctx, uuid)
    emitFanCreator(ctx, uuid)
  }
}

/** Für die Bewerbungs-Prüfung: ist der Spieler aktiver bzw. gesperrter Creator? */
export function creatorStateOf(ctx: AppContext, uuid: string): CreatorState | null {
  return creatorRow(ctx, uuid)?.state ?? null
}

/** Zähler für die Seitenleiste/Übersicht des Team-Bereichs. */
export function creatorCounts(ctx: AppContext): { queue: number, requests: number } {
  const n = (sql: string) => one<{ n: number }>(ctx.db, sql)!.n
  return {
    queue: n("SELECT COUNT(*) AS n FROM creator_symbols WHERE status = 'pending'")
      + n("SELECT COUNT(*) AS n FROM capes WHERE creator_uuid IS NOT NULL AND status = 'pending'"),
    requests: n("SELECT COUNT(*) AS n FROM creator_requests WHERE status = 'open'"),
  }
}
