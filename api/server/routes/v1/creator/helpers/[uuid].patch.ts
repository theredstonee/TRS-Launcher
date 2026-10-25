import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { actingAs } from '../../../../lib/creator-http'
import { updateHelper } from '../../../../lib/creators'
import { limit, paramWith, readJson, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { uuidSchema } from '../../../../lib/schemas'

/** Rechte eines Helfers ändern (§35.9): `{ rights }`. Auch im Team-Modus (`?as=`, `creators.manage`). → `{ helpers }` */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`creator-manage:${auth.uuid}`, RULES.creatorManageUser)
  const helper = paramWith(event, 'uuid', uuidSchema)
  return updateHelper(useCtx(), auth.uuid, helper, await readJson(event, z.unknown()), actingAs(event))
})
