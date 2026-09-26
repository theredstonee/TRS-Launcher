import { defineEventHandler } from 'h3'
import { listCosmeticsPage } from '../../../../lib/admin'
import { useCtx } from '../../../../lib/context'
import { queryWith, requireStaff } from '../../../../lib/http'
import { adminCosmeticListQuery } from '../../../../lib/schemas'

/** Kosmetik-Uploads nach Status (`?status=pending|approved|rejected|reported`, Standard: pending). */
export default defineEventHandler((event) => {
  requireStaff(event)
  const q = queryWith(event, adminCosmeticListQuery)
  return listCosmeticsPage(useCtx(), q)
})
