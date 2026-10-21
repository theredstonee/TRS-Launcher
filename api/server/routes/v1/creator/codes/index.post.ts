import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { actingAs } from '../../../../lib/creator-http'
import { createExtraCode } from '../../../../lib/creators'
import { created, limit, readJson, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Zusatz-Code anlegen (§35.3). → 201 `{ codes }` */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`creator-manage:${auth.uuid}`, RULES.creatorManageUser)
  return created(event, createExtraCode(useCtx(), auth.uuid, await readJson(event, z.unknown()), actingAs(event)))
})
