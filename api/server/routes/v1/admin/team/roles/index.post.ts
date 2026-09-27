import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { created, limit, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { createRole } from '../../../../../lib/team'

const Body = z.strictObject({
  name: z.string().max(64).nullable().optional(),
  color: z.string().regex(/^#[0-9a-f]{6}$/).optional(),
  rank: z.number().int().min(1).max(999).optional(),
  permissions: z.array(z.string().max(40)).max(64).optional(),
  maxSanctionMinutes: z.number().int().min(1).max(3650 * 1440).nullable().optional(),
  public: z.boolean().optional(),
}).required({ name: true, color: true, rank: true })

/** Eigene Rolle anlegen (Rang unter dem eigenen, nur eigene Rechte vergebbar). 201 `{ role }`. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'roles.manage')
  limit(`admin-roles:${staff.uuid}`, RULES.adminSanction)
  const body = await readJson(event, Body)
  return created(event, { role: createRole(useCtx(), staff, { ...body, name: body.name ?? '' }) })
})
