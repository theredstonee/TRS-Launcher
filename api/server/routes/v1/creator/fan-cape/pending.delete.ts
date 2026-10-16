import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { deletePendingFanCape } from '../../../../lib/creators'
import { limit, noContent, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Wartenden Fan-Umhang zurückziehen (§35.3). 204. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`creator-manage:${auth.uuid}`, RULES.creatorManageUser)
  deletePendingFanCape(useCtx(), auth.uuid)
  return noContent(event)
})
