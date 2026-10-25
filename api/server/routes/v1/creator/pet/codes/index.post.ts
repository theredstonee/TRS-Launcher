import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { actingAs } from '../../../../../lib/creator-http'
import { createPetCodes } from '../../../../../lib/creator-pets'
import { created, limit, readJson, requireWebOrUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Codes für das eigene Haustier erstellen (§35.13): `{ count, maxUses, expiresAt?, note? }`. → 201 `{ codes, quota }`, Klartext nur hier. */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`creator-manage:${auth.uuid}`, RULES.creatorManageUser)
  return created(event, createPetCodes(useCtx(), auth.uuid, await readJson(event, z.unknown()), actingAs(event)))
})
