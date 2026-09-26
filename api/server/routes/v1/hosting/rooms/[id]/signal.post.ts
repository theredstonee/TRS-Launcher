import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { signal } from '../../../../../lib/hosting'
import { limit, paramWith, readJson, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { roomIdSchema, signalBody } from '../../../../../lib/schemas'

/** ICE-Signal an den Host bzw. einen angenommenen Gast (Zustellung über /v1/events/me). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'hosting')
  const id = paramWith(event, 'id', roomIdSchema)
  const body = await readJson(event, signalBody)
  limit(`hostingSignal:${auth.uuid}`, RULES.hostingSignalUser)
  limit(`hostingSignalBurst:${auth.uuid}`, RULES.hostingSignalBurstUser)
  return signal(useCtx(), auth.uuid, id, body)
})
