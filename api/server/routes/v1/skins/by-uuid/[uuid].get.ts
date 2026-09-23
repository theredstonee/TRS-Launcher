import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, paramWith, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { uuidSchema } from '../../../../lib/schemas'

/** Skin eines Minecraft-Kontos per UUID (über Mojang, zwischengespeichert). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  limit(`skins:${auth.uuid}`, RULES.skinLookupUser)
  return useCtx().skins.byUuid(uuid)
})
