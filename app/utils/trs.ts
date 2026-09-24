import { z } from 'zod'
// Relativ importiert, damit Tests die Datei ohne Nuxt laden können.
import { t } from './i18n'
import { compareText, formatShortDate, loaderLabels } from './format'

// TRS-Dienste (Umhänge, Freunde, Verwaltung): Schemas für das, was der Kern
// liefert (wird beim Empfang geprüft), und für Eingaben, bevor sie rausgehen.
// Alle Netzwerkzugriffe laufen im Rust-Kern – hier gibt es nur Daten.

const uuid = z.string().regex(/^[0-9a-f]{32}$/)
const capeId = z.string().regex(/^[a-z0-9][a-z0-9_-]{0,39}$/)
const text = (max: number) => z.string().max(max)
const pngDataUrl = z.string().startsWith('data:image/png;base64,').nullable()
/** Umhang-Faktor: mitgelieferte HD-Umhänge bis 8 (512×256), eigene Uploads 1–4 – wie `MAX_CAPE_SCALE` im Kern. */
const capeScale = z.number().int().min(1).max(8)

export const trsStatusSchema = z.object({
  consent: z.enum(['accepted', 'declined']).nullable(),
  account: uuid.nullable(),
  signedIn: z.boolean(),
})

export const trsPrivacySchema = z.object({
  showBadge: z.boolean(),
  showCapeToOthers: z.boolean(),
  presenceVisibility: z.enum(['friends', 'nobody']),
  shareServer: z.boolean(),
})

export const trsMeSchema = z.object({
  uuid,
  name: text(16),
  admin: z.boolean(),
  createdAt: text(40).nullable(),
  settings: trsPrivacySchema,
  activeCapeId: capeId.nullable(),
})

export const trsCapeSchema = z.object({
  id: capeId,
  name: text(48),
  kind: z.enum(['builtin', 'upload', 'other']),
  unlock: z.enum(['free', 'code', 'admin', 'owner', 'other']),
  status: z.enum(['approved', 'pending', 'rejected', 'other']),
  width: z.number().int().positive(),
  height: z.number().int().positive(),
  scale: capeScale,
  frames: z.number().int().min(1).max(64),
  frameTimeMs: z.number().int().min(20).max(10_000).nullable(),
  owned: z.boolean(),
  active: z.boolean(),
  rejectReason: text(200).nullable(),
  texture: pngDataUrl,
})

export const trsRedeemSchema = z.object({ capeId, name: text(48), alreadyOwned: z.boolean() })

export const trsPlayerCapeSchema = z.object({
  uuid,
  badge: z.boolean(),
  capeId: capeId.nullable(),
  upload: z.boolean(),
  scale: capeScale,
  frames: z.number().int().min(1).max(64),
  frameTimeMs: z.number().int().nullable(),
  texture: pngDataUrl,
})

export const trsGameSchema = z.object({
  version: text(32),
  loader: z.enum(['vanilla', 'fabric', 'quilt', 'forge', 'neoforge']),
  server: text(261).optional(),
})

export const trsPresenceSchema = z.object({
  state: z.enum(['online', 'in-game']),
  game: trsGameSchema.nullable(),
  updatedAt: text(40).nullable(),
})

export const trsFriendSchema = z.object({
  uuid,
  name: text(16),
  since: text(40).nullable(),
  presence: trsPresenceSchema.nullable(),
})

const requestEntry = z.object({ uuid, name: text(16), createdAt: text(40).nullable() })

export const trsFriendsSchema = z.object({
  friends: z.array(trsFriendSchema),
  requests: z.object({ incoming: z.array(requestEntry), outgoing: z.array(requestEntry) }),
})

export const trsUserRefSchema = z.object({ uuid, name: text(16) })
export const trsFriendRequestResultSchema = z.object({ status: z.enum(['sent', 'accepted']), user: trsUserRefSchema })
export const trsBlockedSchema = z.object({ uuid, name: text(16), since: text(40).nullable() })

const count = z.number().int().min(0)
export const trsAdminStatsSchema = z.object({
  users: z.object({ total: count, banned: count, activeLast24h: count, online: count }),
  sessions: count,
  capes: z.object({ builtin: count, approved: count, pending: count, rejected: count, reported: count, activeUsers: count }),
  codes: z.object({ active: count, redemptions: count }),
  friendships: count,
  pendingFriendRequests: count,
  eventStreams: count,
})

export const trsAdminCapeSchema = trsCapeSchema.extend({
  owner: trsUserRefSchema.nullable(),
  createdAt: text(40).nullable(),
  reviewedAt: text(40).nullable(),
  reviewedBy: text(64).nullable(),
  reports: z.object({ count, reasons: z.record(z.string(), count) }),
})

export const trsCodeSchema = z.object({
  id: z.number().int().positive(),
  hint: text(8),
  capeId,
  maxUses: count,
  uses: count,
  expiresAt: text(40).nullable(),
  revokedAt: text(40).nullable(),
  note: text(200).nullable(),
  createdAt: text(40).nullable(),
  createdBy: text(80).nullable(),
  code: text(32).optional(),
})

export const trsAdminUserSchema = z.object({
  uuid,
  name: text(16).nullable(),
  known: z.boolean(),
  admin: z.boolean(),
  banned: z
    .object({ reason: text(200).nullable(), bannedAt: text(40).nullable(), bannedBy: text(80).nullable() })
    .nullable(),
  createdAt: text(40).nullable(),
  lastLoginAt: text(40).nullable(),
  settings: trsPrivacySchema.nullable(),
  activeCapeId: capeId.nullable(),
  grantedCapes: z.array(z.object({ capeId, source: text(40), grantedAt: text(40).nullable() })),
  uploads: count,
  friends: count,
  sessions: count,
  online: z.boolean(),
})

export type TrsStatus = z.infer<typeof trsStatusSchema>
export type TrsPrivacy = z.infer<typeof trsPrivacySchema>
export type TrsMe = z.infer<typeof trsMeSchema>
export type TrsCape = z.infer<typeof trsCapeSchema>
export type TrsRedeem = z.infer<typeof trsRedeemSchema>
export type TrsPlayerCape = z.infer<typeof trsPlayerCapeSchema>
export type TrsGame = z.infer<typeof trsGameSchema>
export type TrsPresence = z.infer<typeof trsPresenceSchema>
export type TrsFriend = z.infer<typeof trsFriendSchema>
export type TrsFriends = z.infer<typeof trsFriendsSchema>
export type TrsUserRef = z.infer<typeof trsUserRefSchema>
export type TrsBlocked = z.infer<typeof trsBlockedSchema>
export type TrsAdminStats = z.infer<typeof trsAdminStatsSchema>
export type TrsAdminCape = z.infer<typeof trsAdminCapeSchema>
export type TrsCode = z.infer<typeof trsCodeSchema>
export type TrsAdminUser = z.infer<typeof trsAdminUserSchema>
export type TrsReportReason = 'inappropriate' | 'copyright' | 'impersonation' | 'other'
export type TrsReviewList = 'pending' | 'approved' | 'rejected' | 'reported'

/** Antwort des Kerns prüfen; kaputte Daten werden zu einem verständlichen Fehler. */
export function trsParse<S extends z.ZodType>(schema: S, value: unknown): z.output<S> {
  const r = schema.safeParse(value)
  if (!r.success) throw new Error(t('trs.invalidData'))
  return r.data
}

// --- Eingaben -----------------------------------------------------------------------

const noControl = /^[^\u0000-\u001f\u007f​-‏‪-‮⁠-⁯﻿]*$/

export const trsPlayerNameSchema = z
  .string()
  .trim()
  .regex(/^[A-Za-z0-9_]{1,16}$/, { error: () => t('trs.validation.playerName') })

/** Name oder UUID (mit/ohne Bindestriche). */
export const trsTargetSchema = z
  .string()
  .trim()
  .refine(
    (s) => /^[A-Za-z0-9_]{1,16}$/.test(s) || /^[0-9a-fA-F]{32}$/.test(s) || /^[0-9a-fA-F-]{36}$/.test(s),
    { error: () => t('trs.validation.target') },
  )

/** Einlösecode wie der Server: Groß, ohne Trenner, O→0, I/L→1, 20 Zeichen Crockford-Base32. */
export function trsNormalizeCode(input: string): string | null {
  const s = input
    .toUpperCase()
    .replace(/[\s-]/g, '')
    .replace(/O/g, '0')
    .replace(/[IL]/g, '1')
  return /^[0-9A-HJKMNP-TV-Z]{20}$/.test(s) ? s : null
}

export const trsRedeemCodeSchema = z
  .string()
  .max(64, { error: () => t('trs.validation.codeTooLong') })
  .refine((s) => trsNormalizeCode(s) !== null, { error: () => t('trs.validation.codeFormat') })

export const trsCapeNameSchema = z
  .string()
  .trim()
  .max(32, { error: () => t('trs.validation.max32') })
  .regex(/^[\p{L}\p{N} _.,'!?&()+-]*$/u, { error: () => t('trs.validation.capeNameChars') })

export const trsNoteSchema = z
  .string()
  .trim()
  .max(200, { error: () => t('trs.validation.max200') })
  .regex(noControl, { error: () => t('trs.validation.invalidChars') })

export const trsNewCodesSchema = z.object({
  capeId: capeId,
  maxUses: z
    .number()
    .int()
    .min(1, { error: () => t('trs.validation.minUses') })
    .max(100_000, { error: () => t('trs.validation.maxUses') }),
  count: z
    .number()
    .int()
    .min(1, { error: () => t('trs.validation.minCount') })
    .max(100, { error: () => t('trs.validation.maxCount') }),
  expiresAt: z
    .string()
    .nullable()
    .refine((s) => s === null || !Number.isNaN(Date.parse(s)), { error: () => t('trs.validation.invalidExpiry') })
    .refine((s) => s === null || Date.parse(s) > Date.now(), { error: () => t('trs.validation.expiryPast') }),
  note: trsNoteSchema.nullable(),
})

// --- Helfer -------------------------------------------------------------------------

/** Aktueller Frame eines animierten Umhangs – für alle Spieler gleich (Wanduhr). */
export function trsFrameIndex(now: number, frames: number, frameTimeMs: number | null): number {
  if (frames <= 1 || !frameTimeMs || frameTimeMs <= 0) return 0
  return Math.floor(now / frameTimeMs) % frames
}

/** Kurzlabel, wie man an den Umhang kommt. */
export function trsUnlockLabel(cape: Pick<TrsCape, 'unlock' | 'kind'>): string {
  if (cape.kind === 'upload') return t('trs.unlock.own')
  switch (cape.unlock) {
    case 'free':
      return t('trs.unlock.free')
    case 'code':
      return t('trs.unlock.code')
    case 'admin':
      return t('trs.unlock.team')
    default:
      return t('trs.unlock.locked')
  }
}

/** Status eines eigenen Uploads in Worten. */
export function trsStatusLabel(status: TrsCape['status']): string | null {
  if (status === 'pending') return t('trs.status.pending')
  if (status === 'rejected') return t('trs.status.rejected')
  return null
}

/** „Spielt 1.21.1 (Fabric) auf play.example.net“ usw. */
export function trsPresenceText(presence: TrsPresence | null): string {
  if (!presence) return t('common.status.offline')
  if (presence.state === 'online') return t('trs.presence.online')
  const game = presence.game
  if (!game) return t('trs.presence.inGame')
  const version = game.version
  const server = game.server
  if (game.loader === 'vanilla') {
    return server ? t('trs.presence.playingServer', { version, server }) : t('trs.presence.playing', { version })
  }
  const loader = loaderLabels[game.loader]
  return server
    ? t('trs.presence.playingLoaderServer', { version, loader, server })
    : t('trs.presence.playingLoader', { version, loader })
}

/** Sortierung: im Spiel, online, offline – innerhalb nach Name. */
export function trsSortFriends<T extends { name: string; presence: TrsPresence | null }>(list: T[]): T[] {
  const rank = (f: T) => (f.presence?.state === 'in-game' ? 0 : f.presence ? 1 : 2)
  return [...list].sort((a, b) => rank(a) - rank(b) || compareText(a.name, b.name))
}

interface InstanceLike {
  id: string
  gameVersion: string
  loader: { kind: string }
}

/**
 * Passende Instanz zum Beitreten: gleiche Version + gleicher Loader, sonst
 * gleiche Version, sonst die zuletzt gespielte (die Liste ist danach sortiert).
 */
export function trsJoinInstance<T extends InstanceLike>(instances: T[], game: TrsGame | null): T | null {
  if (!instances.length) return null
  if (game) {
    const exact = instances.find((i) => i.gameVersion === game.version && i.loader.kind === game.loader)
    if (exact) return exact
    const sameVersion = instances.find((i) => i.gameVersion === game.version)
    if (sameVersion) return sameVersion
  }
  return instances[0] ?? null
}

/** Fehler, die still als Zustand angezeigt werden statt als Meldung. */
export function trsIsQuiet(kind: string | undefined): boolean {
  return kind === 'trs_offline' || kind === 'trs_disabled' || kind === 'trs_no_account'
}

/** Datum kurz in der eingestellten Sprache („23.09.2026“ / „9/23/26“), unbekannt → „–“. */
export function trsDate(iso: string | null | undefined): string {
  return formatShortDate(iso)
}

export const TRS_PRIVACY_URL = 'https://github.com/theredstonee/TRS-Launcher/blob/main/PRIVACY.md#trs-services'
