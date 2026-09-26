import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../../lib/context'
import { paramWith, readJson, requireAdmin } from '../../../../../../lib/http'
import { adminUserModeration, muteUser } from '../../../../../../lib/moderation'
import { adminMuteBody, uuidSchema } from '../../../../../../lib/schemas'

/** Im Chat stummschalten (`minutes` fehlt = bis zur Aufhebung). */
export default defineEventHandler(async (event) => {
  const actor = requireAdmin(event)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  const body = await readJson(event, adminMuteBody)
  const ctx = useCtx()
  muteUser(ctx, actor, uuid, body.minutes ?? null, body.reason ?? null)
  return { moderation: adminUserModeration(ctx, uuid) }
})
