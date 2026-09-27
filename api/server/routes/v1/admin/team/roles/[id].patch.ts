import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { updateRole } from '../../../../../lib/team'

const Body = z.strictObject({
  name: z.string().max(64).nullable().optional(),
  color: z.string().regex(/^#[0-9a-f]{6}$/).optional(),
  rank: z.number().int().min(1).max(999).optional(),
  permissions: z.array(z.string().max(40)).max(64).optional(),
  maxSanctionMinutes: z.number().int().min(1).max(3650 * 1440).nullable().optional(),
  public: z.boolean().optional(),
})

/** Rolle ändern: Name, Farbe, Rang, Rechte, Höchstdauer, öffentlich (Owner: nur Farbe/Name/öffentlich). */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'roles.manage')
  limit(`admin-roles:${staff.uuid}`, RULES.adminSanction)
  const id = paramWith(event, 'id', z.string().regex(/^[a-z][a-z0-9_]{1,39}$/))
  const body = await readJson(event, Body)
  return { role: updateRole(useCtx(), staff, id, body) }
})
