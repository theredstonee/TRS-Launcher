import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { removeHelper } from '../../../../lib/creators'
import { limit, noContent, paramWith, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { uuidSchema } from '../../../../lib/schemas'

/** Helfer entfernen oder Einladung zurückziehen (§35.9). 204. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`creator-manage:${auth.uuid}`, RULES.creatorManageUser)
  removeHelper(useCtx(), auth.uuid, paramWith(event, 'uuid', uuidSchema))
  return noContent(event)
})
