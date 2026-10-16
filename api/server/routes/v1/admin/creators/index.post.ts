import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { adminCreateCreator } from '../../../../lib/creators'
import { created, limit, readJson, requireStaff } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Creator anlegen (§35.6): `{ player, tier, mainCode? }`. → 201 `{ creator }` */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'creators.manage')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  return created(event, adminCreateCreator(useCtx(), staff, await readJson(event, z.unknown())))
})
