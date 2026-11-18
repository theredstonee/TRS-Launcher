import { defineEventHandler, setResponseStatus } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { relayWake } from '../../../../lib/craftinghosting'
import { limit, parseWith } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { requireRelay } from '../../../../lib/relay-auth'

const body = z.object({
  host: z.string().max(253),
  player: z.object({ name: z.string().regex(/^\w{1,16}$/), uuid: z.string().max(40).optional() }).strict(),
}).strict()

/** TRS Wake: Server beim Beitreten starten (§43.10). */
export default defineEventHandler(async (event) => {
  const raw = await requireRelay(event)
  let json: unknown = null
  try {
    json = JSON.parse(raw.toString('utf8'))
  } catch { /* parseWith meldet invalid_request */ }
  const b = parseWith(body, json)
  limit(`wake:${b.host.toLowerCase()}`, RULES.craftinghostingWake)
  const res = await relayWake(useCtx(), b.host, b.player)
  setResponseStatus(event, 202)
  return res
})
