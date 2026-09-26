import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../../../lib/context'
import { kick } from '../../../../../../../lib/hosting'
import { limit, noContent, paramWith, readJson, requireUser } from '../../../../../../../lib/http'
import { RULES } from '../../../../../../../lib/ratelimit'
import { kickBody, roomIdSchema, uuidSchema } from '../../../../../../../lib/schemas'

/** Entfernen; `ban` = in dieser Welt sperren, `remember` = für alle künftigen Welten des Hosts. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'hosting')
  const id = paramWith(event, 'id', roomIdSchema)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  const body = await readJson(event, kickBody)
  limit(`hostingManage:${auth.uuid}`, RULES.hostingManageUser)
  kick(useCtx(), auth.uuid, id, uuid, { ban: body?.ban ?? false, remember: body?.remember ?? false })
  return noContent(event)
})
