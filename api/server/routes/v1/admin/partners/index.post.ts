import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { created, limit, readJson, requireStaff } from '../../../../lib/http'
import { adminCreatePartner } from '../../../../lib/partners'
import { RULES } from '../../../../lib/ratelimit'

/** Partner anlegen: `{ name, slug, category, code, prefix, owner, quota: 0|500|1000 }` → 201 `{ partner }`. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'partners.manage')
  limit(`admin-partners:${staff.uuid}`, RULES.adminPartners)
  return created(event, adminCreatePartner(useCtx(), staff, await readJson(event, z.unknown())))
})
