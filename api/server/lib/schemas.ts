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

export const uploadQuery = z.strictObject({
  name: capeNameSchema.optional(),
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
  game: z
    .strictObject({
      version: versionString,
      loader: z.enum(['vanilla', 'fabric', 'quilt', 'forge', 'neoforge']),
      server: serverAddress.optional(),
    })
    .optional(),
})

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
