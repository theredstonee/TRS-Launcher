import { defineEventHandler } from 'h3'
import { bulkReviewCosmetics } from '../../../../lib/admin'
import { useCtx } from '../../../../lib/context'
import { limit, readJson, requireStaff } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { bulkCosmeticsBody } from '../../../../lib/schemas'

/** Sammelaktion: bis zu 50 Kosmetik-Uploads freigeben oder ablehnen (eine Transaktion). */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event)
  limit(`admin-bulk:${staff.uuid}`, RULES.adminBulk)
  const body = await readJson(event, bulkCosmeticsBody)
  return bulkReviewCosmetics(useCtx(), staff.uuid, body.ids, body.action, body.reason)
})
