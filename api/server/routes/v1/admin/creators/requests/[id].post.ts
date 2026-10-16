import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { adminDecideRequest } from '../../../../../lib/creators'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Anfrage beantworten (§35.6): `{ status: "accepted"|"declined", reason? }`; angenommener Aufstieg hebt die Stufe um eins. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'creators.manage')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  const id = paramWith(event, 'id', z.coerce.number().int().min(1).max(Number.MAX_SAFE_INTEGER))
  return adminDecideRequest(useCtx(), staff, id, await readJson(event, z.unknown()))
})
