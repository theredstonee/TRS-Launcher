import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, noContent } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { cancelPairCode } from '../../../../lib/remote'
import { requireDevice } from '../../../../lib/remote-http'

/** PC: offenen Kopplungs-Code zurückziehen (Dialog geschlossen). Immer 204. */
export default defineEventHandler((event) => {
  const { auth, device } = requireDevice(event, 'write', 'desktop')
  limit(`remoteManage:${auth.uuid}`, RULES.remoteManageUser)
  cancelPairCode(useCtx(), device)
  return noContent(event)
})
