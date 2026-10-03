import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, noContent } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { removeDevice } from '../../../../lib/remote'
import { requireDevice } from '../../../../lib/remote-http'

/** Dieses Gerät abmelden (§33.1): alle Kopplungen weg, Gegenstellen bekommen `remote_pairing` (`removed`). */
export default defineEventHandler((event) => {
  const { auth, device } = requireDevice(event, 'write')
  limit(`remoteManage:${auth.uuid}`, RULES.remoteManageUser)
  removeDevice(useCtx(), auth.uuid, device.id)
  return noContent(event)
})
