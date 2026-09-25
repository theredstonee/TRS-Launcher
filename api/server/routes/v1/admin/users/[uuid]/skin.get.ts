import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, requireAdmin } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { uuidSchema } from '../../../../../lib/schemas'

/** Skin eines Kontos per UUID für die Admin-Seite (Gesicht des Uploaders), wie `/v1/skins/by-uuid`. */
export default defineEventHandler(async (event) => {
  const actor = requireAdmin(event)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  limit(`skins:${actor}`, RULES.skinLookupUser)
  return useCtx().skins.byUuid(uuid)
})
