import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { setRoomContent } from '../../../../../lib/hosting'
import { limit, paramWith, readJson, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { HOSTING_CONTENT, hostingContentBody, roomIdSchema } from '../../../../../lib/schemas'

/** Host: Mod-Liste + Resource Pack teilen (nur Metadaten, ≤ 300 Mods, Körper ≤ 256 KB). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'hosting')
  const id = paramWith(event, 'id', roomIdSchema)
  const body = await readJson(event, hostingContentBody, HOSTING_CONTENT.bodyLimit)
  limit(`hostingManage:${auth.uuid}`, RULES.hostingManageUser)
  return { room: setRoomContent(useCtx(), auth.uuid, id, body) }
})
