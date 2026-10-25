import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { adminSetPetExtras } from '../../../../../lib/pet-extras'
import { RULES } from '../../../../../lib/ratelimit'
import { uuidSchema } from '../../../../../lib/schemas'

/** Gefolge setzen (§35.13): `{ cosmeticIds: [...] }` (Platz `pet`, höchstens 4, Reihenfolge). → `{ extras, showExtras, max }` */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'creators.manage')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  return adminSetPetExtras(useCtx(), staff, uuid, await readJson(event, z.unknown()))
})
