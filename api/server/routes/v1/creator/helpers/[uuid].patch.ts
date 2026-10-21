import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { updateHelper } from '../../../../lib/creators'
import { limit, paramWith, readJson, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { uuidSchema } from '../../../../lib/schemas'

/** Rechte eines Helfers ändern (§35.9): `{ rights }`. → `{ helpers }` */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`creator-manage:${auth.uuid}`, RULES.creatorManageUser)
  const helper = paramWith(event, 'uuid', uuidSchema)
  return updateHelper(useCtx(), auth.uuid, helper, await readJson(event, z.unknown()))
})
