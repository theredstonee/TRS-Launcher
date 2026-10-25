import { z } from 'zod'
// Relativ importiert, damit Tests die Datei ohne Nuxt laden können.
import { resample, sampleFrames, stack, type Crop, type Rgba } from './capeImage'
import type { MessageKey } from './i18n'

// Creator-Programm (API §35): Schemas für das, was der Kern liefert (beim
// Empfang geprüft), und reine Regeln für die Oberfläche – Code-Muster,
// 30-Tage-Regel, Stufen, Codes, Meilensteine und das Umwandeln eines Bildes
// (PNG/GIF/APNG) in den Fan-Symbol-Streifen. Getestet in tests/creator.test.ts.

export const creatorTiers = ['starter', 'partner', 'top'] as const
export type CreatorTier = (typeof creatorTiers)[number]
export const creatorStates = ['active', 'revoked', 'banned'] as const
export type CreatorState = (typeof creatorStates)[number]
export const milestones = ['bronze', 'silver', 'gold', 'diamond'] as const
export type Milestone = (typeof milestones)[number]

/** Aktive Fans je Meilenstein (§35.1). */
export const milestoneAt: Record<Milestone, number> = { bronze: 100, silver: 500, gold: 1000, diamond: 5000 }
/** Rahmenfarbe des Fan-Symbols je Meilenstein (wie im TRS Client). */
export const milestoneColor: Record<Milestone, string> = { bronze: '#cd7f32', silver: '#c9d1dc', gold: '#ffcf4d', diamond: '#5ee6e6' }
/** Follower, ab denen eine Stufe beantragt werden kann. */
export const tierFollowers: Record<CreatorTier, number> = { starter: 0, partner: 1000, top: 10_000 }
/** Rechte eines Helfers (§35.9) – Stufe, Anträge, Helfer und Vorschau-Builds bleiben beim Creator. */
export const helperRights = ['stats', 'codes', 'uploads', 'packs'] as const
export type HelperRight = (typeof helperRights)[number]
/** Höchstzahl Helfer (angenommen + eingeladen) je Stufe. */
export const helperLimit: Record<CreatorTier, number> = { starter: 1, partner: 3, top: 10 }
/** Einen anderen Creator unterstützen geht erst so viele Tage nach dem letzten Start. */
export const SWITCH_DAYS = 30
const DAY_MS = 86_400_000

// --- Codes ------------------------------------------------------------------------------

export const CREATOR_CODE_RE = /^[A-Z0-9][A-Z0-9-]{3,18}[A-Z0-9]$/
export const CREATOR_CODE_MAX = 20

/** Eingabe → Creator-Code (groß) oder `null`. */
export function normalizeCreatorCode(input: string): string | null {
  const s = input.trim().toUpperCase()
  // 20 Zeichen ohne Bindestrich sind ein Umhang-Code (§5.9), kein Creator-Code (§35.1).
  if (s.length === 20 && !s.includes('-')) return null
  return CREATOR_CODE_RE.test(s) ? s : null
}

/** Gehört die Eingabe ins Creator-Einlösen? Creator-Codes brauchen kein Präfix (z. B. `PIXELMAUS`); Umhang-Codes
 *  haben 20 Zeichen (auch in Fünfergruppen mit Bindestrich) und passen deshalb nicht ins Muster. */
export function isCreatorCode(input: string): boolean {
  return normalizeCreatorCode(input) !== null
}

/** Beim Tippen: groß, nur A–Z, 0–9 und `-`, höchstens 20 Zeichen. */
export function cleanCodeInput(input: string): string {
  return input.toUpperCase().replace(/[^A-Z0-9-]/g, '').slice(0, CREATOR_CODE_MAX)
}

// --- Schemas (Antworten des Kerns) ------------------------------------------------------

const uuid = z.string().regex(/^[0-9a-f]{32}$/)
const text = (max: number) => z.string().max(max)
const time = text(40).nullable()
const count = z.number().int().min(0)
const pngDataUrl = z.string().startsWith('data:image/png;base64,')
const tier = z.enum(creatorTiers)
const milestone = z.enum(milestones).nullable()
const capeId = z.string().regex(/^[a-z0-9][a-z0-9_-]{0,39}$/)
const code = z.string().regex(CREATOR_CODE_RE)
const packCode = z.string().regex(/^TRS-[0-9A-Z]{4}-[0-9A-Z]{4}$/)
const userRef = z.object({ uuid, name: text(16) })
const creatorRef = z.object({ uuid, name: text(16), tier })
const rights = z.array(z.enum(helperRights)).max(4)

export const creatorSymbolSchema = z.object({
  sha256: z.string().regex(/^[0-9a-f]{64}$/),
  size: z.number().int().min(16).max(64),
  frames: z.number().int().min(1).max(8),
  frameMs: z.number().int().min(50).max(1000),
  image: pngDataUrl.nullable(),
})

export const creatorChannelSchema = z.object({
  platform: text(32),
  url: z.string().startsWith('https://').max(500).nullable(),
  followers: count.nullable(),
})

export const fanCapeSchema = z.object({
  capeId,
  name: text(48),
  texture: pngDataUrl.nullable(),
  scale: z.number().int().min(1).max(16),
  frames: z.number().int().min(1).max(32),
})

export const creatorCardSchema = z.object({
  uuid,
  name: text(16),
  tier,
  channels: z.array(creatorChannelSchema).max(8),
  symbol: creatorSymbolSchema.nullable(),
  fanCape: fanCapeSchema.nullable(),
  milestone,
})

export const creatorSupportSchema = z.object({
  creator: creatorCardSchema.nullable(),
  startedAt: time,
  lastCreatorUuid: uuid.nullable(),
  switchAvailableAt: time,
})

export const creatorCodeSchema = z.object({
  code,
  kind: z.enum(['main', 'extra']),
  uses: count,
  maxUses: count.nullable(),
  expiresAt: time,
  active: z.boolean(),
  createdAt: time,
})

export const creatorLimitsSchema = z.object({
  extraCodes: count.nullable(),
  maxUsesPerCode: count.nullable(),
  symbolFrames: z.number().int().min(1).max(8),
  fanCape: z.enum(['standard', 'hd']).nullable(),
})

const dailySchema = z.object({ day: z.string().regex(/^\d{4}-\d{2}-\d{2}$/), newFans: count, redemptions: count })
export const creatorStatsSchema = z.object({
  activeFans: count,
  redemptions: count,
  fanCapeHolders: count,
  newPlayers: count,
  packInstalls: count,
  daily: z.array(dailySchema).max(30),
})

const rejection = z.object({ reason: text(500).nullable(), at: time }).nullable()
export const creatorRequestSchema = z.object({
  id: text(40),
  kind: z.enum(['upgrade', 'team_cape']),
  status: z.enum(['open', 'accepted', 'declined']),
  createdAt: time,
  reason: text(500).nullable(),
})

export const creatorPackRefSchema = z.object({ code: packCode, name: text(64), installs: count, listed: z.boolean() })

export const creatorSelfSchema = z.object({
  uuid,
  name: text(16),
  tier,
  state: z.enum(creatorStates),
  reason: text(500).nullable(),
  since: time,
  channels: z.array(creatorChannelSchema).max(8),
  codes: z.array(creatorCodeSchema).max(500),
  limits: creatorLimitsSchema,
  stats: creatorStatsSchema,
  milestone,
  nextMilestone: z.object({ name: z.enum(milestones), at: count }).nullable(),
  symbol: z.object({ current: creatorSymbolSchema.nullable(), pending: creatorSymbolSchema.nullable(), rejected: rejection }),
  fanCape: z.object({ current: fanCapeSchema.nullable(), pending: fanCapeSchema.nullable(), rejected: rejection }),
  requests: z.array(creatorRequestSchema).max(50),
  packs: z.array(creatorPackRefSchema).max(200),
  /** Helfer (nur für den Creator selbst, §35.9). */
  helpers: z
    .array(z.object({ uuid, name: text(16), rights, status: z.enum(['pending', 'active']), createdAt: time }))
    .max(50)
    .default([]),
  /** Ein Helfer schaut (`?as=`): seine Rechte. */
  viewer: z.object({ helper: z.boolean(), rights }).nullable().default(null),
})

export const myCreatorSchema = z.object({
  support: creatorSupportSchema,
  fanCapes: z.array(z.object({ capeId, name: text(48), creator: userRef })).max(200),
  blocked: z.array(userRef).max(500),
  showFanSymbols: z.boolean(),
  creator: creatorSelfSchema.nullable(),
  application: z.object({ id: text(40), status: z.enum(['new', 'review', 'interview', 'accepted', 'rejected', 'withdrawn']), createdAt: time }).nullable(),
  /** Creator, bei denen der Spieler hilft. */
  helping: z.array(z.object({ creator: creatorRef, rights })).max(50).default([]),
  helperInvites: z.array(z.object({ creator: userRef, rights, createdAt: time })).max(50).default([]),
})

export const creatorRedeemSchema = z.object({
  creator: creatorCardSchema,
  supportChanged: z.boolean(),
  switchAvailableAt: time,
  fanCapeGranted: z.boolean(),
})

export const previewBuildSchema = z.object({
  id: text(40),
  product: z.enum(['launcher', 'client']),
  version: text(40),
  notes: text(1000).nullable(),
  url: z.string().startsWith('https://').max(500),
  sha256: z.string().regex(/^[0-9a-f]{64}$/).nullable(),
  publishedAt: time,
})

export const listedPacksSchema = z.object({
  packs: z.array(
    z.object({
      code: packCode,
      name: text(64),
      summary: text(200).nullable(),
      mcVersion: text(32).nullable(),
      loader: z.enum(['vanilla', 'fabric', 'quilt', 'forge', 'neoforge']).nullable(),
      installs: count,
      creator: creatorRef,
    }),
  ).max(50),
  total: count,
})

export const creatorQueueItemSchema = z.object({
  id: text(40),
  kind: z.enum(['symbol', 'fan_cape']),
  creator: creatorRef,
  symbol: creatorSymbolSchema.nullable(),
  cape: z.object({ capeId, name: text(48), frames: z.number().int().min(1).max(32), scale: z.number().int().min(1).max(16), texture: pngDataUrl.nullable() }).nullable(),
  createdAt: time,
})

export const adminCreatorSchema = z.object({
  uuid,
  name: text(16),
  tier,
  state: z.enum(creatorStates),
  reason: text(500).nullable(),
  fans: count,
  mainCode: code.nullable(),
  since: time,
})

export const adminCreatorRequestSchema = z.object({
  id: text(40),
  kind: z.enum(['upgrade', 'team_cape']),
  status: z.enum(['open', 'accepted', 'declined']),
  createdAt: time,
  reason: text(500).nullable(),
  creator: creatorRef.nullable(),
  channelUrl: z.string().startsWith('https://').max(500).nullable(),
  followers: count.nullable(),
  note: text(1000).nullable(),
  text: text(2000).nullable(),
})

export const creatorUserRefSchema = userRef

/** Arten von `creator_updated` (§35.7). */
export const creatorUpdateKinds = [
  'symbol_approved',
  'symbol_rejected',
  'cape_approved',
  'cape_rejected',
  'tier_changed',
  'revoked',
  'restored',
  'request_answered',
  'milestone',
  'became_creator',
  'helper_joined',
  'helper_left',
  // Creator-Haustiere (§35.13): zugeordnet, entfernt, Wunsch beantwortet (`reason` = Antwort des Teams).
  'pet_assigned',
  'pet_removed',
  'pet_request_answered',
] as const
export type CreatorUpdateKind = (typeof creatorUpdateKinds)[number]

/** Echtzeit-Ereignisse (§35.7) – Teil von `liveEventSchema`. */
export const creatorEventSchemas = [
  z.object({
    type: z.literal('creator_updated'),
    kind: z.enum(creatorUpdateKinds),
    reason: text(500).nullable(),
    tier: tier.nullable(),
    milestone,
    helper: userRef.nullable(),
  }),
  z.object({ type: z.literal('creator_milestone'), creator: userRef, milestone: z.enum(milestones) }),
  z.object({ type: z.literal('creator_helper_invite'), creator: userRef, rights }),
  z.object({ type: z.literal('creator_helper_removed'), creator: userRef.nullable() }),
] as const
export type CreatorUpdatedEvent = z.infer<(typeof creatorEventSchemas)[0]>
export type CreatorMilestoneEvent = z.infer<(typeof creatorEventSchemas)[1]>
export type CreatorHelperInviteEvent = z.infer<(typeof creatorEventSchemas)[2]>
export type CreatorHelperRemovedEvent = z.infer<(typeof creatorEventSchemas)[3]>
export type CreatorEvent = z.infer<(typeof creatorEventSchemas)[number]>

export type CreatorSymbol = z.infer<typeof creatorSymbolSchema>
export type CreatorChannel = z.infer<typeof creatorChannelSchema>
export type FanCape = z.infer<typeof fanCapeSchema>
export type CreatorCard = z.infer<typeof creatorCardSchema>
export type CreatorSupport = z.infer<typeof creatorSupportSchema>
export type CreatorCode = z.infer<typeof creatorCodeSchema>
export type CreatorLimits = z.infer<typeof creatorLimitsSchema>
export type CreatorStats = z.infer<typeof creatorStatsSchema>
export type CreatorRequest = z.infer<typeof creatorRequestSchema>
export type CreatorPackRef = z.infer<typeof creatorPackRefSchema>
export type CreatorSelf = z.infer<typeof creatorSelfSchema>
export type MyCreator = z.infer<typeof myCreatorSchema>
export type CreatorRedeem = z.infer<typeof creatorRedeemSchema>
export type PreviewBuild = z.infer<typeof previewBuildSchema>
export type ListedPacks = z.infer<typeof listedPacksSchema>
export type ListedPack = ListedPacks['packs'][number]
export type CreatorQueueItem = z.infer<typeof creatorQueueItemSchema>
export type AdminCreator = z.infer<typeof adminCreatorSchema>
export type AdminCreatorRequest = z.infer<typeof adminCreatorRequestSchema>

export type CreatorHelper = CreatorSelf['helpers'][number]
export type CreatorHelping = MyCreator['helping'][number]
export type CreatorHelperInvite = MyCreator['helperInvites'][number]

// --- Dashboard-Reiter und Helfer ----------------------------------------------------------

export const dashboardTabs = ['stats', 'codes', 'pet', 'symbol', 'cape', 'previews', 'packs', 'helpers', 'tier'] as const
export type DashboardTab = (typeof dashboardTabs)[number]

/** Welches Recht ein Reiter für Helfer braucht (`null` = nur der Creator selbst). */
const tabRight: Record<DashboardTab, HelperRight | null> = {
  stats: 'stats',
  codes: 'codes',
  // Haustier-Codes erstellen/widerrufen dürfen Helfer mit „Codes“ (der Antrag bleibt beim Creator).
  pet: 'codes',
  symbol: 'uploads',
  cape: 'uploads',
  packs: 'packs',
  previews: null,
  helpers: null,
  tier: null,
}

/** Sichtbare Reiter: der Creator sieht alle, ein Helfer nur die seiner Rechte (§35.9). */
export function visibleTabs(viewer: { helper: boolean; rights: readonly HelperRight[] } | null): DashboardTab[] {
  if (!viewer?.helper) return [...dashboardTabs]
  return dashboardTabs.filter((tab) => {
    const right = tabRight[tab]
    return right !== null && viewer.rights.includes(right)
  })
}

/** Darf (noch) ein Helfer eingeladen werden? */
export function canInviteHelper(helpers: readonly unknown[], tier: CreatorTier): boolean {
  return helpers.length < helperLimit[tier]
}

/** Freunde, die man einladen kann: nicht man selbst, nicht schon Helfer/eingeladen. */
export function invitableFriends<F extends { uuid: string }>(friends: readonly F[], helpers: readonly { uuid: string }[], me: string | null): F[] {
  const taken = new Set(helpers.map((h) => h.uuid))
  return friends.filter((f) => f.uuid !== me && !taken.has(f.uuid))
}

// --- 30-Tage-Regel ----------------------------------------------------------------------

export interface SwitchState {
  /** Ein ANDERER Creator ist noch gesperrt. */
  locked: boolean
  /** Ganze Tage bis zum Wechsel (aufgerundet), 0 = jetzt. */
  daysLeft: number
  /** Ab wann ein Wechsel geht (`null` = jederzeit). */
  at: Date | null
}

/**
 * Wann ein anderer Creator geht: `switchAvailableAt` vom Server, sonst 30 Tage
 * nach dem letzten Start. Ohne bisherigen Creator geht es sofort.
 */
export function switchState(support: Pick<CreatorSupport, 'startedAt' | 'switchAvailableAt' | 'lastCreatorUuid'>, now = Date.now()): SwitchState {
  const parse = (iso: string | null) => {
    const ms = iso ? Date.parse(iso) : Number.NaN
    return Number.isFinite(ms) ? ms : null
  }
  const started = parse(support.startedAt)
  const at = parse(support.switchAvailableAt) ?? (started !== null ? started + SWITCH_DAYS * DAY_MS : null)
  if (at === null || (!support.lastCreatorUuid && started === null) || at <= now) return { locked: false, daysLeft: 0, at: null }
  return { locked: true, daysLeft: Math.ceil((at - now) / DAY_MS), at: new Date(at) }
}

/** Tage seit dem Start der Unterstützung (abgerundet, nie negativ). */
export function supportDays(startedAt: string | null, now = Date.now()): number {
  const ms = startedAt ? Date.parse(startedAt) : Number.NaN
  return Number.isFinite(ms) ? Math.max(0, Math.floor((now - ms) / DAY_MS)) : 0
}

/** Würde das Einlösen eines Codes von `creatorUuid` die Unterstützung ändern? (Derselbe Creator geht immer.) */
export function canSupport(support: CreatorSupport, creatorUuid: string, now = Date.now()): boolean {
  if (support.lastCreatorUuid === creatorUuid || support.creator?.uuid === creatorUuid) return true
  return !switchState(support, now).locked
}

// --- Stufen ------------------------------------------------------------------------------

export function tierAtLeast(current: CreatorTier, min: CreatorTier): boolean {
  return creatorTiers.indexOf(current) >= creatorTiers.indexOf(min)
}

export function nextTier(current: CreatorTier): CreatorTier | null {
  return creatorTiers[creatorTiers.indexOf(current) + 1] ?? null
}

/** Vorteile je Stufe (Texte unter `creator.tiers.<stufe>.*`). */
export const tierPerks: Record<CreatorTier, MessageKey[]> = {
  starter: ['creator.tiers.starter.perk1', 'creator.tiers.starter.perk2', 'creator.tiers.starter.perk3', 'creator.tiers.starter.perk4'],
  partner: ['creator.tiers.partner.perk1', 'creator.tiers.partner.perk2', 'creator.tiers.partner.perk3', 'creator.tiers.partner.perk4', 'creator.tiers.partner.perk5'],
  top: ['creator.tiers.top.perk1', 'creator.tiers.top.perk2', 'creator.tiers.top.perk3', 'creator.tiers.top.perk4'],
}

/** Farbe der Stufen-Plakette (Klassen der Oberfläche). */
export const tierClass: Record<CreatorTier, string> = {
  starter: 'bg-sky-400/10 text-sky-300 ring-sky-400/40',
  partner: 'bg-violet-500/10 text-violet-300 ring-violet-400/45',
  top: 'bg-lamp-400/10 text-lamp-300 ring-lamp-400/45',
}

// --- Codes ------------------------------------------------------------------------------

export type CodeStatus = 'active' | 'off' | 'usedUp' | 'expired'

export function codeStatus(c: Pick<CreatorCode, 'active' | 'uses' | 'maxUses' | 'expiresAt'>, now = Date.now()): CodeStatus {
  if (!c.active) return 'off'
  if (c.maxUses !== null && c.uses >= c.maxUses) return 'usedUp'
  const until = c.expiresAt ? Date.parse(c.expiresAt) : Number.NaN
  if (Number.isFinite(until) && until <= now) return 'expired'
  return 'active'
}

/** Zusatz-Codes, die gegen die Grenze der Stufe zählen – nur eingeschaltete (API §35.x). */
export function extraCodeCount(codes: Pick<CreatorCode, 'kind' | 'active'>[]): number {
  return codes.filter((c) => c.kind === 'extra' && c.active).length
}

/** Darf noch ein Zusatz-Code angelegt werden? (`extraCodes: null` = unbegrenzt) */
export function canAddCode(codes: Pick<CreatorCode, 'kind' | 'active'>[], limits: Pick<CreatorLimits, 'extraCodes'>): boolean {
  return limits.extraCodes === null || extraCodeCount(codes) < limits.extraCodes
}

/** Anteil jedes Codes an allen Einlösungen (ganze Prozent). */
export function codeShares(codes: Pick<CreatorCode, 'code' | 'uses'>[]): { code: string; uses: number; percent: number }[] {
  const total = codes.reduce((s, c) => s + c.uses, 0)
  return codes.map((c) => ({ code: c.code, uses: c.uses, percent: total ? Math.round((c.uses / total) * 100) : 0 }))
}

/** Datum aus dem Datumsfeld (`2026-10-31`) → Ende des Tages in Ortszeit als ISO. */
export function expiryFromDate(date: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return null
  const d = new Date(`${date}T23:59:59`)
  return Number.isNaN(d.getTime()) ? null : d.toISOString()
}

// --- Statistik & Meilensteine -----------------------------------------------------------

/** Summe der letzten `days` Tage (Tageswerte älteste zuerst). */
export function recentSum(daily: CreatorStats['daily'], key: 'newFans' | 'redemptions', days = 7): number {
  return daily.slice(-days).reduce((s, d) => s + d[key], 0)
}

/** Fortschritt zum nächsten Meilenstein in Prozent (0–100). */
export function milestoneProgress(activeFans: number, next: { at: number } | null, current: Milestone | null): number {
  if (!next) return 100
  const from = current ? milestoneAt[current] : 0
  const span = Math.max(1, next.at - from)
  return Math.max(0, Math.min(100, Math.round(((activeFans - from) / span) * 100)))
}

// --- Fan-Symbol aus einem Bild ----------------------------------------------------------

export const SYMBOL_MIN = 16
export const SYMBOL_MAX = 64
export const SYMBOL_MAX_FRAMES = 8
export const SYMBOL_MAX_BYTES = 256 * 1024
export const SYMBOL_FRAME_MS_DEFAULT = 150

/** Quadratischer Ausschnitt in der Mitte. */
export function centerSquare(width: number, height: number): Crop {
  const side = Math.min(width, height)
  return { x: Math.floor((width - side) / 2), y: Math.floor((height - side) / 2), w: side, h: side }
}

/**
 * Kantenlänge des Symbols für eine Quelle: kleine Bilder bleiben (mindestens 16),
 * große werden möglichst pixelgenau verkleinert (größter Teiler bis 64), sonst 64.
 */
export function symbolSize(width: number, height: number): number {
  const side = Math.min(width, height)
  if (side <= SYMBOL_MAX) return Math.max(SYMBOL_MIN, side)
  for (let s = SYMBOL_MAX; s >= SYMBOL_MIN; s--) {
    if (side % s === 0) return s
  }
  return SYMBOL_MAX
}

/** Bildtempo aus der Animation: Gesamtdauer gleichmäßig auf die behaltenen Bilder, 50–1000 ms. */
export function symbolFrameMs(durationMs: number, keptFrames: number): number {
  if (!Number.isFinite(durationMs) || durationMs <= 0 || keptFrames <= 0) return SYMBOL_FRAME_MS_DEFAULT
  return Math.min(1000, Math.max(50, Math.round(durationMs / keptFrames / 10) * 10))
}

export interface SymbolStrip {
  strip: Rgba
  size: number
  frames: number
  /** So viele Bilder hatte die Quelle (mehr als erlaubt → gleichmäßig ausgedünnt). */
  sourceFrames: number
}

/**
 * Frames (PNG = 1, GIF/APNG = mehrere) → senkrechter Streifen aus quadratischen
 * Bildern, höchstens `maxFrames` (Starter 1, sonst 8). Jedes Bild wird mittig
 * quadratisch zugeschnitten und auf `size` gebracht.
 */
export function buildSymbolStrip(frames: Rgba[], maxFrames: number, size?: number): SymbolStrip | null {
  const first = frames[0]
  if (!first || first.width < 1 || first.height < 1) return null
  const keep = Math.max(1, Math.min(SYMBOL_MAX_FRAMES, maxFrames))
  const s = Math.max(SYMBOL_MIN, Math.min(SYMBOL_MAX, Math.round(size ?? symbolSize(first.width, first.height))))
  const picked = sampleFrames(frames.length, keep).map((i) => frames[i]!)
  const scaled = picked.map((f) => resample(f, centerSquare(f.width, f.height), s, s))
  return { strip: stack(scaled), size: s, frames: scaled.length, sourceFrames: frames.length }
}

/** Hat der Streifen sichtbare Pixel? Sonst lehnt der Kern ab. */
export function symbolVisible(strip: Rgba): boolean {
  for (let i = 3; i < strip.data.length; i += 4) {
    if (strip.data[i]! > 0) return true
  }
  return false
}

// --- Creator-Haustier ------------------------------------------------------------------
// Ein Creator hat höchstens ein eigenes Haustier (Kosmetik mit slot `pet`). Der
// Besitzer erstellt Codes dafür – die Summe der Einlösungen zählt gegen ein
// Monatslimit je Stufe. Ohne Haustier gibt es einen Antrag ans Team.

export const petRequestStatuses = ['open', 'in_progress', 'done', 'rejected'] as const
export type PetRequestStatus = (typeof petRequestStatuses)[number]
export const PET_CODE_MAX_COUNT = 20
export const PET_CODE_MAX_USES = 100
export const PET_NOTE_MAX = 100
export const PET_SPECIES_MAX = 60
export const PET_REQUEST_TEXT_MAX = 1000
export const PET_COLORS_MAX = 200
export const PET_IMAGE_MAX_BYTES = 2 * 1024 * 1024

export const petRequestSchema = z.object({
  id: text(40),
  status: z.enum(petRequestStatuses),
  species: text(240).nullable(),
  /** Beschreibung des Wunsches. */
  text: text(4000),
  colors: text(800).nullable(),
  hasImage: z.boolean(),
  /** Antwort des Teams. */
  answer: text(4000).nullable(),
  createdAt: time,
})

export const creatorPetStateSchema = z.object({
  pet: z
    .object({
      cosmeticId: z.string().regex(/^[A-Za-z0-9_.-]{1,64}$/),
      name: text(96),
      card: pngDataUrl.nullable(),
      count: z.number().int().min(1).max(4).nullable(),
      available: z.boolean(),
    })
    .nullable(),
  limit: z.object({ month: z.string().regex(/^\d{4}-\d{2}$/).nullable(), used: count, max: count, resetsAt: time }).nullable(),
  request: petRequestSchema.nullable(),
  /** Vom Server: darf jetzt einen Wunsch stellen (`null` = unbekannt, dann rechnet `canRequestPet`). */
  canRequest: z.boolean().nullable(),
})

export const petCodeSchema = z.object({
  id: text(40),
  /** Klartext – nur direkt nach dem Erstellen. */
  code: z.string().regex(/^[A-Z0-9-]{4,40}$/).nullable(),
  codeHint: text(48).nullable(),
  maxUses: count,
  uses: count,
  revoked: z.boolean(),
  expiresAt: time,
  note: text(400).nullable(),
  createdAt: time,
})

export type PetRequest = z.infer<typeof petRequestSchema>
export type CreatorPetState = z.infer<typeof creatorPetStateSchema>
export type CreatorPet = NonNullable<CreatorPetState['pet']>
export type PetLimit = NonNullable<CreatorPetState['limit']>
export type PetCode = z.infer<typeof petCodeSchema>

/** Noch freie Einlösungen in diesem Monat (nie negativ). */
export function petRemaining(limit: Pick<PetLimit, 'used' | 'max'> | null): number {
  return limit ? Math.max(0, limit.max - limit.used) : 0
}

/** Verbrauch in Prozent (0–100) für den Balken. */
export function petLimitPercent(limit: Pick<PetLimit, 'used' | 'max'> | null): number {
  if (!limit || limit.max <= 0) return limit && limit.used > 0 ? 100 : 0
  return Math.max(0, Math.min(100, Math.round((limit.used / limit.max) * 100)))
}

/** Wann das Limit wieder frei ist: `resetsAt` vom Server, sonst der Erste des Folgemonats (`2026-10` → 1. Nov. 2026). */
export function petLimitResetsAt(month: string | null, resetsAt: string | null = null): Date | null {
  const at = resetsAt ? Date.parse(resetsAt) : Number.NaN
  if (Number.isFinite(at)) return new Date(at)
  const m = month?.match(/^(\d{4})-(\d{2})$/)
  if (!m) return null
  const mon = Number(m[2])
  if (mon < 1 || mon > 12) return null
  return new Date(Number(m[1]), mon, 1)
}

/** Kalendermonat `YYYY-MM` als Datum (für die Anzeige „Oktober 2026“). */
export function petMonthDate(month: string | null): Date | null {
  const next = petLimitResetsAt(month)
  return next ? new Date(next.getFullYear(), next.getMonth() - 1, 1) : null
}

export interface PetCodesForm {
  count: number | ''
  maxUses: number | ''
  until: string
  note: string
}

export type PetCodesProblem = 'count' | 'uses' | 'limit' | 'until' | 'note'

/** Eingaben prüfen, bevor etwas rausgeht. `remaining` = noch freie Einlösungen im Monat. */
export function checkPetCodes(form: PetCodesForm, remaining: number, now = Date.now()): PetCodesProblem | null {
  const count = Number(form.count)
  const uses = Number(form.maxUses)
  if (form.count === '' || !Number.isInteger(count) || count < 1 || count > PET_CODE_MAX_COUNT) return 'count'
  if (form.maxUses === '' || !Number.isInteger(uses) || uses < 1 || uses > PET_CODE_MAX_USES) return 'uses'
  if (form.until) {
    const at = expiryFromDate(form.until)
    if (!at || Date.parse(at) <= now) return 'until'
  }
  if (form.note.trim().length > PET_NOTE_MAX) return 'note'
  if (count * uses > remaining) return 'limit'
  return null
}

/** Höchstzahl Codes, die bei `maxUses` noch ins Limit passen (0–20). */
export function petCodesFit(remaining: number, maxUses: number): number {
  if (!Number.isInteger(maxUses) || maxUses < 1) return 0
  return Math.max(0, Math.min(PET_CODE_MAX_COUNT, Math.floor(remaining / maxUses)))
}

export type PetCodeStatus = 'active' | 'revoked' | 'usedUp' | 'expired'

export function petCodeStatus(c: Pick<PetCode, 'revoked' | 'uses' | 'maxUses' | 'expiresAt'>, now = Date.now()): PetCodeStatus {
  if (c.revoked) return 'revoked'
  if (c.uses >= c.maxUses) return 'usedUp'
  const until = c.expiresAt ? Date.parse(c.expiresAt) : Number.NaN
  if (Number.isFinite(until) && until <= now) return 'expired'
  return 'active'
}

/** Kurzform eines Codes in der Liste (der Klartext kommt nur einmal). */
export function petCodeLabel(c: Pick<PetCode, 'code' | 'codeHint'>): string {
  if (c.code) return c.code
  const hint = c.codeHint ?? ''
  return hint.startsWith('…') ? hint : `…${hint}`
}

/** Darf ein (neuer) Wunsch gestellt werden? Sagt es der Server, gilt das; sonst sperren offene und laufende Wünsche. */
export function canRequestPet(state: Pick<CreatorPetState, 'pet' | 'request'> & { canRequest?: boolean | null }): boolean {
  if (state.pet) return false
  if (typeof state.canRequest === 'boolean') return state.canRequest
  return !state.request || state.request.status === 'rejected' || state.request.status === 'done'
}

export type PetRequestProblem = 'species' | 'description' | 'colors'

/** Wunsch prüfen: Tierart 1–60, Beschreibung 1–1000, Farben höchstens 200 Zeichen. */
export function checkPetRequest(wish: { species: string; description: string; colors: string }): PetRequestProblem | null {
  const species = wish.species.trim()
  const description = wish.description.trim()
  if (!species || species.length > PET_SPECIES_MAX) return 'species'
  if (!description || description.length > PET_REQUEST_TEXT_MAX) return 'description'
  if (wish.colors.trim().length > PET_COLORS_MAX) return 'colors'
  return null
}

export type PetImageProblem = 'type' | 'size'

/** Referenzbild: PNG oder JPG (Typ der Datei), höchstens 2 MB. Die Bytes prüft der Kern noch einmal. */
export function petImageProblem(file: { type: string; size: number }): PetImageProblem | null {
  if (!['image/png', 'image/jpeg'].includes(file.type)) return 'type'
  if (file.size > PET_IMAGE_MAX_BYTES) return 'size'
  return null
}

/** Bildart aus den ersten Bytes (`null` = weder PNG noch JPG). */
export function petImageKind(bytes: Uint8Array): 'image/png' | 'image/jpeg' | null {
  const png = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]
  if (bytes.length >= 8 && png.every((b, i) => bytes[i] === b)) return 'image/png'
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'image/jpeg'
  return null
}
