import { z } from 'zod'
import { CAPE_ID, SERVER_ID, normalizeRedeemCode, normalizeUuid } from './ids'

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

export const createCodesBody = z.strictObject({
  capeId: capeIdSchema,
  maxUses: z.int().min(1).max(100_000).default(1),
  count: z.int().min(1).max(100).default(1),
  expiresAt: z.iso.datetime({ offset: true }).optional(),
  note: plainText(200).optional(),
})

export const grantCapeBody = z.strictObject({ capeId: capeIdSchema })

export const adminCapeListQuery = z.strictObject({
  status: z.enum(['pending', 'approved', 'rejected', 'reported']).default('pending'),
})

export const codeIdSchema = z.coerce.number().int().min(1).max(Number.MAX_SAFE_INTEGER)
