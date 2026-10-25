import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { adminAssignPet } from '../../../../../lib/creator-pets'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { uuidSchema } from '../../../../../lib/schemas'

/** Haustier zuordnen (§35.13): `{ cosmeticId }` (Platz `pet`, eins je Creator, eins je Haustier). → `{ pet }` */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'creators.manage')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  return adminAssignPet(useCtx(), staff, uuid, await readJson(event, z.unknown()))
})
