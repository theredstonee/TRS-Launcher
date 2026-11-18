import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { relayResolve } from '../../../../lib/craftinghosting'
import { queryWith } from '../../../../lib/http'
import { requireRelay } from '../../../../lib/relay-auth'

/** TRS Wake: Zustand + Ziel eines <name>.trs-launcher.de (§43.10). */
export default defineEventHandler(async (event) => {
  await requireRelay(event)
  const q = queryWith(event, z.object({ host: z.string().max(253) }))
  return relayResolve(useCtx(), q.host)
})
