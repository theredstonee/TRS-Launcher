import { defineEventHandler, getHeader } from 'h3'
import { useCtx } from '../../../lib/context'
import { clientIp } from '../../../lib/http'
import { apiQuota, authenticatePartnerKey } from '../../../lib/partners'

/** Kontingent `{ total, used, requested }`. */
export default defineEventHandler((event) => {
  const p = authenticatePartnerKey(useCtx(), getHeader(event, 'authorization'), clientIp(event))
  return apiQuota(useCtx(), p)
})
