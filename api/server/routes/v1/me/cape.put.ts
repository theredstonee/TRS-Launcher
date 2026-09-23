import { defineEventHandler } from 'h3'
import { setActiveCape } from '../../../lib/capes'
import { useCtx } from '../../../lib/context'
import { readJson, requireUser } from '../../../lib/http'
import { setCapeBody } from '../../../lib/schemas'

export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const body = await readJson(event, setCapeBody)
  return { activeCape: setActiveCape(useCtx(), auth.uuid, body.capeId) }
})
