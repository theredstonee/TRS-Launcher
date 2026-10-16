import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { blockCreator } from '../../../../../lib/creators'
import { limit, readJson, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Creator per Code blockieren (§35.2): Symbol und Abzeichen sind für dich überall weg, Codes nicht einlösbar. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`creator-fan:${auth.uuid}`, RULES.creatorFanUser)
  return blockCreator(useCtx(), auth.uuid, await readJson(event, z.unknown()))
})
