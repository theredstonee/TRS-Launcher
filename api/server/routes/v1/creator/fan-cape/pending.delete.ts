import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { actingAs } from '../../../../lib/creator-http'
import { deletePendingFanCape } from '../../../../lib/creators'
import { limit, noContent, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Wartenden Fan-Umhang zurückziehen (§35.3). 204. */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`creator-manage:${auth.uuid}`, RULES.creatorManageUser)
  deletePendingFanCape(useCtx(), auth.uuid, actingAs(event))
  return noContent(event)
})
