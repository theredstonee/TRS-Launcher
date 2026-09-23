import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, paramWith, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { mcNameSchema } from '../../../../lib/schemas'

/** Skin eines beliebigen Minecraft-Kontos per Name (über Mojang, zwischengespeichert). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event)
  const name = paramWith(event, 'name', mcNameSchema)
  limit(`skins:${auth.uuid}`, RULES.skinLookupUser)
  return useCtx().skins.byName(name)
})
