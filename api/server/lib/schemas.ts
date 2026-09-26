import { z } from 'zod'
import { CAPE_ID, COSMETIC_ID, SERVER_ID, normalizeRedeemCode, normalizeUuid } from './ids'
import { TEMPLATE_ID, WEARABLE_SLOTS } from './templates'

/** Alle Eingaben laufen durch diese Schemas (Whitelist, `strict` = unbekannte Felder → 400). */

export const uuidSchema = z
  .string()
  .max(36)
  .transform((s, ctx) => {
    const u = normalizeUuid(s)
    if (!u) {
      ctx.addIssue({ code: 'custom', message: 'must be a Minecraft UUID (32 hex digits, dashes optional)' })
      return z.NEVER
    }
    return u
  })

export const mcNameSchema = z
  .string()
  .regex(/^[A-Za-z0-9_]{1,16}$/, 'must be a Minecraft name (1-16 characters: A-Z, a-z, 0-9, _)')

export const serverIdSchema = z.string().regex(SERVER_ID, 'must be the serverId returned by /v1/auth/challenge')

export const capeIdSchema = z.string().regex(CAPE_ID, 'invalid cape id')

export const cosmeticIdSchema = z.string().regex(COSMETIC_ID, 'invalid cosmetic id')

/** Freitext ohne Steuerzeichen, getrimmt. */
const plainText = (max: number) =>
  z
    .string()
    .trim()
    .min(1)
    .max(max)
    .refine((s) => !/[\p{Cc}\p{Cf}\p{Co}\p{Cn}]/u.test(s), 'must not contain control characters')

export const capeNameSchema = z
  .string()
  .trim()
  .min(1)
  .max(32)
  .regex(/^[\p{L}\p{N} _.,'!?&()+-]+$/u, 'only letters, digits, spaces and . , \' ! ? & ( ) + - _')

export const verifyBody = z.strictObject({
  username: mcNameSchema,
  serverId: serverIdSchema,
})

export const logoutBody = z.strictObject({ all: z.boolean().optional() }).optional()

export const settingsPatch = z
  .strictObject({
    showBadge: z.boolean(),
    showCapeToOthers: z.boolean(),
    presenceVisibility: z.enum(['friends', 'nobody']),
    shareServer: z.boolean(),
    showCosmeticsToOthers: z.boolean(),
    chatReadReceipts: z.boolean(),
    chatTypingIndicator: z.boolean(),
  })
  .partial()
  .refine((o) => Object.keys(o).length > 0, 'at least one setting is required')

export const setCapeBody = z.strictObject({ capeId: capeIdSchema.nullable() })

export const redeemBody = z.strictObject({
  code: z
    .string()
    .max(64)
    .transform((s, ctx) => {
      const c = normalizeRedeemCode(s)
      if (!c) {
        ctx.addIssue({ code: 'custom', message: 'invalid code format' })
        return z.NEVER
      }
      return c
    }),
})

/** Umhang-Upload: `frames` (optional) muss zur Datei passen, `frameTimeMs` ist bei Animation Pflicht. */
export const uploadQuery = z.strictObject({
  name: capeNameSchema.optional(),
  frames: z.coerce.number().int().min(1).max(16).optional(),
  frameTimeMs: z.coerce.number().int().min(50).max(10_000).optional(),
})

export const reportBody = z.strictObject({
  reason: z.enum(['inappropriate', 'copyright', 'impersonation', 'other']),
  note: plainText(200).optional(),
})

export const lookupBody = z.strictObject({
  uuids: z.array(uuidSchema).min(1).max(100),
})

const versionString = z.string().regex(/^[0-9A-Za-z._+ -]{1,32}$/, 'invalid version')
/** Hostname oder IPv4 mit optionalem Port – keine Pfade, kein Schema. */
const serverAddress = z
  .string()
  .max(261)
  .regex(/^(?=.{1,253}(:|$))[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*(:\d{1,5})?$/, 'invalid server address')

export const presenceBody = z.strictObject({
  state: z.enum(['online', 'in-game', 'offline']),
  /** Quelle (fehlt bei alten Clients – siehe `POST /v1/presence`). */
  via: z.enum(['client', 'launcher']).optional(),
  game: z
    .strictObject({
      version: versionString,
      loader: z.enum(['vanilla', 'fabric', 'quilt', 'forge', 'neoforge']),
      server: serverAddress.optional(),
    })
    .optional(),
})

export type PresenceBody = z.infer<typeof presenceBody>

/** Ziel einer Freundschaftsanfrage/Blockierung: Minecraft-Name oder UUID. */
export const targetBody = z.strictObject({
  target: z
    .string()
    .trim()
    .min(1)
    .max(36)
    .transform((s, ctx): { uuid: string } | { name: string } => {
      const u = normalizeUuid(s)
      if (u) return { uuid: u }
      if (/^[A-Za-z0-9_]{1,16}$/.test(s)) return { name: s }
      ctx.addIssue({ code: 'custom', message: 'must be a Minecraft name or UUID' })
      return z.NEVER
    }),
})

export const reasonBody = z.strictObject({ reason: plainText(200).optional() }).optional()

export const createCodesBody = z
  .strictObject({
    capeId: capeIdSchema.optional(),
    cosmeticId: cosmeticIdSchema.optional(),
    maxUses: z.int().min(1).max(100_000).default(1),
    count: z.int().min(1).max(100).default(1),
    expiresAt: z.iso.datetime({ offset: true }).optional(),
    note: plainText(200).optional(),
  })
  .refine((o) => (o.capeId === undefined) !== (o.cosmeticId === undefined), 'exactly one of capeId or cosmeticId is required')

export const grantCapeBody = z.strictObject({ capeId: capeIdSchema })

/** Umhang einem Freund anbieten. */
export const capeOfferBody = z.strictObject({ capeId: capeIdSchema, friend: uuidSchema })

export const adminCapeListQuery = z.strictObject({
  status: z.enum(['pending', 'approved', 'rejected', 'reported']).default('pending'),
})

// ---------------------------------------------------------------- Kosmetik, Emotes, Skins

export const templateIdSchema = z.string().regex(TEMPLATE_ID, 'invalid template id')

/** `{ hat?, wings?, back?, aura? }`: ID = anlegen, null = ablegen, fehlend = unverändert. */
const slotValue = cosmeticIdSchema.nullable().optional()
export const equipBody = z
  .strictObject({ hat: slotValue, wings: slotValue, back: slotValue, aura: slotValue } satisfies
    Record<(typeof WEARABLE_SLOTS)[number], typeof slotValue>)
  .refine((o) => Object.values(o).some((v) => v !== undefined), 'at least one slot is required')

export const cosmeticUploadQuery = z.strictObject({
  template: templateIdSchema,
  name: capeNameSchema.optional(),
  frameTimeMs: z.coerce.number().int().min(50).max(10_000).optional(),
})

export const grantCosmeticBody = z.strictObject({ cosmeticId: cosmeticIdSchema })

export const adminCosmeticListQuery = adminCapeListQuery

export const guideQuery = z.strictObject({
  scale: z.coerce.number().int().min(1).max(4).default(1),
})

export const emoteBody = z.strictObject({ emote: cosmeticIdSchema })

export const skinChangedBody = z.strictObject({}).optional()

/** `?uuids=a,b,c` – 1–200 UUIDs (mit oder ohne Bindestriche), Duplikate zählen einmal. */
export const playerStreamQuery = z.strictObject({
  uuids: z
    .string()
    .max(200 * 37)
    .transform((raw, ctx) => {
      const out = new Set<string>()
      for (const part of raw.split(',')) {
        const u = normalizeUuid(part)
        if (!u) {
          ctx.addIssue({ code: 'custom', message: 'must be a comma-separated list of Minecraft UUIDs' })
          return z.NEVER
        }
        out.add(u)
      }
      if (out.size === 0 || out.size > 200) {
        ctx.addIssue({ code: 'custom', message: 'must contain 1 to 200 UUIDs' })
        return z.NEVER
      }
      return [...out]
    }),
})

export const codeIdSchema = z.coerce.number().int().min(1).max(Number.MAX_SAFE_INTEGER)

// ---------------------------------------------------------------- TRS-Sync

/** Skin-ID der Launcher-Bibliothek: 12 Hex-Zeichen klein. */
export const SYNC_SKIN_ID = /^[0-9a-f]{12}$/
export const syncSkinIdSchema = z.string().regex(SYNC_SKIN_ID, 'invalid skin id')

/** Skin-Name: 1–48 Zeichen, keine Steuer- und Bidi-Steuerzeichen (Emojis sind erlaubt). */
const syncSkinName = z
  .string()
  .trim()
  .min(1)
  .max(48)
  .refine((s) => !/[\p{Cc}\u2028\u2029\u202a-\u202e\u2066-\u2069]/u.test(s), 'must not contain control characters')

const syncSkinVariant = z.enum(['classic', 'slim'])

/** Standard-Base64 mit Padding. Die Größe begrenzt der Body (s. SYNC_SKIN_BODY_LIMIT) bzw. die PNG-Prüfung. */
const base64 = z
  .string()
  .min(1)
  .max(256 * 1024)
  .regex(/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/, 'must be standard base64')

export const syncSkinPutBody = z.strictObject({
  name: syncSkinName,
  variant: syncSkinVariant,
  png: base64,
})

export const syncSkinPatchBody = z
  .strictObject({ name: syncSkinName.optional(), variant: syncSkinVariant.optional() })
  .refine((o) => o.name !== undefined || o.variant !== undefined, 'name or variant is required')

const syncUpdatedAt = z.iso.datetime({ offset: true })

/** Beliebiges JSON-Objekt, unverändert durchgereicht (kein Kopieren → kein `__proto__`-Umbiegen). */
const jsonObject = z.custom<Record<string, unknown>>(
  (v) => typeof v === 'object' && v !== null && !Array.isArray(v),
  'must be a JSON object',
)

export const syncPresetsBody = z.strictObject({ data: jsonObject, updatedAt: syncUpdatedAt })

/** Nur Theme (inkl. Akzent) und Sprache – Java-/Speicher-Optionen werden nie synchronisiert. */
const settingValue = (max: number) => z.string().min(1).max(max).regex(/^[A-Za-z0-9_-]+$/, 'only letters, digits, _ and -')
export const syncSettingsBody = z.strictObject({
  data: z.strictObject({
    theme: settingValue(32).optional(),
    accent: settingValue(32).optional(),
    language: settingValue(16).optional(),
  }),
  updatedAt: syncUpdatedAt,
})

// ---------------------------------------------------------------- Chat, Meldungen, Moderation

export const conversationIdSchema = z.string().regex(/^c[0-9a-f]{20}$/, 'invalid conversation id')
export const messageIdSchema = z.string().regex(/^m[0-9a-f]{20}$/, 'invalid message id')
export const attachmentIdSchema = z.string().regex(/^a[0-9a-f]{24}$/, 'invalid attachment id')
export const reportIdSchema = z.string().regex(/^r[0-9a-f]{16}$/, 'invalid report id')

/** Rohtext; gesäubert und gezählt wird in safety.ts (Codepoints, nach dem Säubern). */
const messageText = z.string().max(8000)

const inviteSchema = z.strictObject({
  address: serverAddress,
  name: z.string().trim().min(1).max(32).optional(),
})

export const sendMessageBody = z.strictObject({
  text: messageText.optional(),
  replyTo: messageIdSchema.optional(),
  attachments: z.array(attachmentIdSchema).max(10).optional(),
  invite: inviteSchema.optional(),
  /** Weltkarte (§21): nur der Host des Raums. */
  world: z.strictObject({ roomId: z.string().regex(/^h[0-9a-f]{20}$/, 'invalid world id') }).optional(),
  /** Idempotenz: vom Client erzeugt (z. B. UUID), gleiche nonce = gleiche Nachricht. */
  nonce: z.string().regex(/^[A-Za-z0-9_-]{8,64}$/, 'nonce must be 8-64 characters of A-Z, a-z, 0-9, _ or -').optional(),
})

export const editMessageBody = z.strictObject({ text: messageText })

export const openDmBody = z.strictObject({ uuid: uuidSchema })

const groupNameRaw = z.string().min(1).max(200)
export const createGroupBody = z.strictObject({
  name: groupNameRaw,
  members: z.array(uuidSchema).max(24).default([]),
})
export const renameGroupBody = z.strictObject({ name: groupNameRaw })
export const addMembersBody = z.strictObject({ members: z.array(uuidSchema).min(1).max(24) })
export const transferOwnerBody = z.strictObject({ uuid: uuidSchema })

export const listMessagesQuery = z
  .strictObject({
    before: z.coerce.number().int().min(1).max(Number.MAX_SAFE_INTEGER).optional(),
    after: z.coerce.number().int().min(0).max(Number.MAX_SAFE_INTEGER).optional(),
    limit: z.coerce.number().int().min(1).max(100).default(50),
  })
  .refine((q) => q.before === undefined || q.after === undefined, 'use either before or after')

export const listConversationsQuery = z.strictObject({
  cursor: z.string().max(100).optional(),
  limit: z.coerce.number().int().min(1).max(100).default(50),
})

export const readBody = z.strictObject({ seq: z.int().min(0).max(Number.MAX_SAFE_INTEGER) })
export const unreadBody = z.strictObject({ seq: z.int().min(1).max(Number.MAX_SAFE_INTEGER).optional() }).optional()

/** Stummschalten: `muted: false` = aus; `until` fehlt = unbefristet. */
export const muteConversationBody = z.strictObject({
  muted: z.boolean(),
  until: z.iso.datetime({ offset: true }).optional(),
})

export const typingBody = z.strictObject({ typing: z.boolean() })

export const attachmentQuery = z.strictObject({ thumb: z.enum(['0', '1', 'true', 'false']).optional() })

export const serverStatusQuery = z.strictObject({ address: serverAddress })

export const reportReasonSchema = z.enum(['insult_hate', 'spam', 'inappropriate', 'scam_phishing', 'harassment', 'other'])

export const chatReportBody = z
  .strictObject({
    kind: z.enum(['message', 'image', 'player', 'group']),
    reason: reportReasonSchema,
    note: plainText(500).optional(),
    messageId: messageIdSchema.optional(),
    attachmentId: attachmentIdSchema.optional(),
    uuid: uuidSchema.optional(),
    conversationId: conversationIdSchema.optional(),
  })
  .refine((b) => b.kind !== 'message' || b.messageId !== undefined, 'messageId is required for kind=message')
  .refine((b) => b.kind !== 'image' || b.attachmentId !== undefined, 'attachmentId is required for kind=image')
  .refine((b) => b.kind !== 'player' || b.uuid !== undefined, 'uuid is required for kind=player')
  .refine((b) => b.kind !== 'group' || b.conversationId !== undefined, 'conversationId is required for kind=group')

export const adminReportListQuery = z.strictObject({
  status: z.enum(['open', 'in_review', 'resolved', 'active', 'all']).default('active'),
  kind: z.enum(['message', 'image', 'player', 'group']).optional(),
  target: uuidSchema.optional(),
  cursor: z.string().max(100).optional(),
  limit: z.coerce.number().int().min(1).max(100).default(50),
})

export const adminReportStatusBody = z.strictObject({ status: z.enum(['open', 'in_review']) })

export const adminReportActionBody = z
  .strictObject({
    action: z.enum(['delete_message', 'warn', 'mute', 'ban', 'dismiss', 'resolve']),
    reason: plainText(200).optional(),
    minutes: z.int().min(5).max(525_600).optional(),
    keepOpen: z.boolean().optional(),
    includeRelated: z.boolean().optional(),
  })
  .refine((b) => b.minutes === undefined || b.action === 'mute', 'minutes is only allowed for action=mute')

export const adminNoteBody = z.strictObject({ text: plainText(2000) })

export const adminMuteBody = z.strictObject({
  minutes: z.int().min(5).max(525_600).optional(),
  reason: plainText(200).optional(),
})

export const adminWarnBody = z.strictObject({ reason: plainText(200) })

export const wordFilterBody = z.strictObject({
  word: z.string().trim().min(2).max(64),
  mode: z.enum(['word', 'contains']).default('word'),
  action: z.enum(['block', 'mask']).default('mask'),
})

export const wordIdSchema = z.coerce.number().int().min(1).max(Number.MAX_SAFE_INTEGER)

export const auditQuery = z.strictObject({
  ref: z.string().regex(/^[a-z0-9]{1,40}$/).optional(),
  target: uuidSchema.optional(),
  before: z.coerce.number().int().min(1).max(Number.MAX_SAFE_INTEGER).optional(),
  limit: z.coerce.number().int().min(1).max(200).default(100),
})

export const eventsMeQuery = z.strictObject({ lastEventId: z.string().max(64).optional() })

// ---------------------------------------------------------------- Welt-Hosting (§21)

export const roomIdSchema = z.string().regex(/^h[0-9a-f]{20}$/, 'invalid world id')

const roomName = z.string().min(1).max(200)
const mcVersion = z.string().regex(/^[0-9A-Za-z][0-9A-Za-z._+ -]{0,31}$/, 'must be a Minecraft version like 1.21.4')
const loader = z.enum(['vanilla', 'fabric', 'forge', 'neoforge', 'quilt'])
const gameMode = z.enum(['survival', 'creative', 'adventure', 'spectator'])
const maxPlayers = z.number().int().min(2).max(10)
const visibility = z.enum(['friends', 'invited'])

export const createRoomBody = z.strictObject({
  name: roomName,
  mcVersion,
  loader,
  maxPlayers: maxPlayers.default(8),
  gameMode: gameMode.default('survival'),
  pvp: z.boolean().default(true),
  cheats: z.boolean().default(false),
  open: z.boolean().default(true),
  visibility: visibility.default('friends'),
})

export const updateRoomBody = z
  .strictObject({
    name: roomName.optional(),
    mcVersion: mcVersion.optional(),
    loader: loader.optional(),
    maxPlayers: maxPlayers.optional(),
    gameMode: gameMode.optional(),
    pvp: z.boolean().optional(),
    cheats: z.boolean().optional(),
    open: z.boolean().optional(),
    visibility: visibility.optional(),
  })
  .refine((b) => Object.keys(b).length > 0, 'at least one field is required')

export const heartbeatBody = z.strictObject({ players: z.number().int().min(1).max(10).optional() }).optional()

export const hostingInviteBody = z.strictObject({ uuid: uuidSchema, chat: z.boolean().default(true) })

export const kickBody = z
  .strictObject({ ban: z.boolean().default(false), remember: z.boolean().default(false) })
  .optional()

export const joinBody = z.union([
  z.strictObject({ roomId: roomIdSchema }),
  z.strictObject({ code: z.string().min(1).max(16) }),
])

export const signalBody = z.strictObject({
  to: uuidSchema,
  kind: z.enum(['offer', 'answer', 'candidate', 'bye']),
  sid: z.string().regex(/^[A-Za-z0-9_-]{1,32}$/, 'sid must be 1-32 characters of A-Z, a-z, 0-9, _ or -').optional(),
  // Opak (z. B. JSON oder SDP) – Länge prüft die Route gegen limits.hostingMaxSignalData.
  data: z.string().max(8192),
})
