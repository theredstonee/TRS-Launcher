import { defineEventHandler } from 'h3'
import { audit } from '../../../../lib/admin'
import { createCodes } from '../../../../lib/codes'
import { useCtx } from '../../../../lib/context'
import { created, readJson, requireAdmin } from '../../../../lib/http'
import { createCodesBody } from '../../../../lib/schemas'

/** Erzeugt 1–100 Codes. Der Klartext steht NUR in dieser Antwort. */
export default defineEventHandler(async (event) => {
  const actor = requireAdmin(event)
  const body = await readJson(event, createCodesBody)
  const ctx = useCtx()
  const result = createCodes(ctx, actor, body)
  audit(ctx, actor, 'codes.create', null, `${body.count}x ${body.capeId} (max ${body.maxUses})`)
  return created(event, result)
})
