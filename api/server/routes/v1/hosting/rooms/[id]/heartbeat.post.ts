import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { heartbeat } from '../../../../../lib/hosting'
import { paramWith, readJson, requireUser } from '../../../../../lib/http'
import { heartbeatBody, roomIdSchema } from '../../../../../lib/schemas'

/** Alle ~30 s vom Host; ohne Herzschlag schließt der Raum nach 90 s. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'hosting')
  const id = paramWith(event, 'id', roomIdSchema)
  const body = await readJson(event, heartbeatBody)
  return heartbeat(useCtx(), auth.uuid, id, body?.players)
})
