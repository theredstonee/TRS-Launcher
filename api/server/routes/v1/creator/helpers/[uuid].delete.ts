import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { actingAs } from '../../../../lib/creator-http'
import { removeHelper } from '../../../../lib/creators'
import { limit, noContent, paramWith, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { uuidSchema } from '../../../../lib/schemas'

/** Helfer entfernen oder Einladung zurückziehen (§35.9), auch im Team-Modus (`?as=`). 204. */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`creator-manage:${auth.uuid}`, RULES.creatorManageUser)
  removeHelper(useCtx(), auth.uuid, paramWith(event, 'uuid', uuidSchema), actingAs(event))
  return noContent(event)
})
