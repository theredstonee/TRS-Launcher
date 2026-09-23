import { defineEventHandler } from 'h3'
import { audit } from '../../../../lib/admin'
import { createCodes } from '../../../../lib/codes'
import { useCtx } from '../../../../lib/context'
import { created, readJson, requireAdmin } from '../../../../lib/http'
import { createCodesBody } from '../../../../lib/schemas'

/** Erzeugt 1–100 Codes für einen Umhang (`capeId`) oder ein Kosmetik-Teil/Emote (`cosmeticId`). Klartext NUR in dieser Antwort. */
export default defineEventHandler(async (event) => {
  const actor = requireAdmin(event)
  const body = await readJson(event, createCodesBody)
  const ctx = useCtx()
  const common = { maxUses: body.maxUses, count: body.count, expiresAt: body.expiresAt, note: body.note }
  const result = body.capeId !== undefined
    ? createCodes(ctx, actor, { capeId: body.capeId, ...common })
    : createCodes(ctx, actor, { cosmeticId: body.cosmeticId!, ...common })
  audit(ctx, actor, 'codes.create', null, `${body.count}x ${body.capeId ?? `cosmetic:${body.cosmeticId}`} (max ${body.maxUses})`)
  return created(event, result)
})
