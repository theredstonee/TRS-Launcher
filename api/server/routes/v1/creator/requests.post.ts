import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { actingAs } from '../../../lib/creator-http'
import { createRequest } from '../../../lib/creators'
import { created, limit, readJson, requireWebOrUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'

/** Anfrage ans Team (§35.3): Stufen-Aufstieg oder Team-Umhang. Eine offene je Art. → 201 `{ requests }` */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`creator-request:${auth.uuid}`, RULES.creatorRequestUser)
  return created(event, createRequest(useCtx(), auth.uuid, await readJson(event, z.unknown()), actingAs(event)))
})
