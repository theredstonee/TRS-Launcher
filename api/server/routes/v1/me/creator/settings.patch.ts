import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { setCreatorSettings } from '../../../../lib/creators'
import { limit, readJson, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** `showFanSymbols` (§35.2): aus = der Lookup liefert dir für ANDERE keine Fan-Daten. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`creator-fan:${auth.uuid}`, RULES.creatorFanUser)
  return setCreatorSettings(useCtx(), auth.uuid, await readJson(event, z.unknown()))
})
