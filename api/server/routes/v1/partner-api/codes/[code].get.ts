import { defineEventHandler, getHeader } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { clientIp, paramWith } from '../../../../lib/http'
import { apiCodeStatus, authenticatePartnerKey } from '../../../../lib/partners'

/** Status eines eigenen Einmal-Codes – nie Spielerdaten. Fremd/unbekannt → 404. */
export default defineEventHandler((event) => {
  const p = authenticatePartnerKey(useCtx(), getHeader(event, 'authorization'), clientIp(event))
  return apiCodeStatus(useCtx(), p, paramWith(event, 'code', z.string().max(20)))
})
