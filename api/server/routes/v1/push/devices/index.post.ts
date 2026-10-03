import { defineEventHandler, setResponseStatus } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, readJson, requireUser } from '../../../../lib/http'
import { registerDevice } from '../../../../lib/push'
import { RULES } from '../../../../lib/ratelimit'
import { pushDeviceBody } from '../../../../lib/schemas'

/** Gerät anmelden (§33.2): 201 neu, 200 = gleicher UnifiedPush-Endpunkt aktualisiert. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`pushRegister:${auth.uuid}`, RULES.pushRegisterUser)
  const body = await readJson(event, pushDeviceBody)
  const r = await registerDevice(useCtx(), auth, body)
  setResponseStatus(event, r.created ? 201 : 200)
  return r.device
})
