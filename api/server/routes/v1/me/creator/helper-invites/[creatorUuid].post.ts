import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { answerHelperInvite } from '../../../../../lib/creators'
import { limit, paramWith, readJson, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { uuidSchema } from '../../../../../lib/schemas'

/** Helfer-Einladung annehmen/ablehnen (§35.9): `{ accept }`. → `{ helping, helperInvites }` */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`creator-fan:${auth.uuid}`, RULES.creatorFanUser)
  const creator = paramWith(event, 'creatorUuid', uuidSchema)
  return answerHelperInvite(useCtx(), auth.uuid, creator, await readJson(event, z.unknown()))
})
