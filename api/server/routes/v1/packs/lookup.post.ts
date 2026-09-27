import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { limit, readJson, requireUser } from '../../../lib/http'
import { lookupPacks } from '../../../lib/packs'
import { RULES } from '../../../lib/ratelimit'

/** Update-Prüfung im Launcher: bis 100 Codes auf einmal. Unbekannte/abgelaufene fehlen in der Antwort. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'read')
  limit(`packLookup:${auth.uuid}`, RULES.packLookupUser)
  const body = await readJson(event, z.strictObject({ codes: z.array(z.string().max(32)).min(1).max(100) }))
  return { packs: lookupPacks(useCtx(), body.codes) }
})
