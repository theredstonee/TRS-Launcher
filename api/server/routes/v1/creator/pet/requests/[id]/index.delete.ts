import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { actingAs } from '../../../../../../lib/creator-http'
import { withdrawPetRequest } from '../../../../../../lib/creator-pets'
import { limit, noContent, paramWith, requireWebOrUser } from '../../../../../../lib/http'
import { RULES } from '../../../../../../lib/ratelimit'

/** Offenen Haustier-Wunsch zurückziehen (§35.13). → 204 */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`creator-manage:${auth.uuid}`, RULES.creatorManageUser)
  const id = paramWith(event, 'id', z.coerce.number().int().min(1).max(Number.MAX_SAFE_INTEGER))
  withdrawPetRequest(useCtx(), auth.uuid, id, actingAs(event))
  return noContent(event)
})
