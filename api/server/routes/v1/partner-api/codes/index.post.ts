import { defineEventHandler, getHeader } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { clientIp, created, readJson } from '../../../../lib/http'
import { apiCreateCodes, authenticatePartnerKey } from '../../../../lib/partners'

/** Partner-API (§44.5): `{ count 1..100, label?, ref? }` → 201 `{ codes, quota }`; mit `ref` idempotent. */
export default defineEventHandler(async (event) => {
  const p = authenticatePartnerKey(useCtx(), getHeader(event, 'authorization'), clientIp(event))
  return created(event, apiCreateCodes(useCtx(), p, await readJson(event, z.unknown())))
})
