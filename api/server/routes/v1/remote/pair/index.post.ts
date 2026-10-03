import { defineEventHandler, setResponseHeader } from 'h3'
import { useCtx } from '../../../../lib/context'
import { created, limit } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { createPairCode } from '../../../../lib/remote'
import { requireDevice } from '../../../../lib/remote-http'

/** PC: Kopplungs-Code erzeugen (§33.2) – 2 min gültig, einmalig, ersetzt einen offenen Code dieses PCs. */
export default defineEventHandler((event) => {
  const { auth, device } = requireDevice(event, 'write', 'desktop')
  limit(`remotePair:${auth.uuid}`, RULES.remotePairUser)
  setResponseHeader(event, 'Cache-Control', 'no-store')
  return created(event, createPairCode(useCtx(), device))
})
