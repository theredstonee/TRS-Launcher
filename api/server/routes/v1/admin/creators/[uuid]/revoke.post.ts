import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { adminRevokeCreator } from '../../../../../lib/creators'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { uuidSchema } from '../../../../../lib/schemas'

/** Status entziehen oder sperren (§35.6): `{ reason, ban, removeFanCapes }`. → `{ creator }` */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'creators.manage')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  return adminRevokeCreator(useCtx(), staff, uuid, await readJson(event, z.unknown()))
})
