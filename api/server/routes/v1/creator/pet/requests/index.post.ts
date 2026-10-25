import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { actingAs } from '../../../../../lib/creator-http'
import { createPetRequest } from '../../../../../lib/creator-pets'
import { created, limit, readJson, requireWebOrUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Wunsch „Eigenes Haustier“ (§35.13): `{ species, description, colors? }`. → 201 `{ requests }` */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`creator-request:${auth.uuid}`, RULES.creatorRequestUser)
  return created(event, createPetRequest(useCtx(), auth.uuid, await readJson(event, z.unknown()), actingAs(event)))
})
