import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, noContent, paramWith, requireUser } from '../../../../lib/http'
import { deleteDevice } from '../../../../lib/push'
import { RULES } from '../../../../lib/ratelimit'
import { pushDeviceIdSchema } from '../../../../lib/schemas'

/** Gerät abmelden (§33.2). Wartende Abruf-Einträge verschwinden mit. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`pushManage:${auth.uuid}`, RULES.pushManageUser)
  const id = paramWith(event, 'id', pushDeviceIdSchema)
  deleteDevice(useCtx(), auth.uuid, id)
  return noContent(event)
})
