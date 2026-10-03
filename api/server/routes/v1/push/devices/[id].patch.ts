import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, paramWith, readJson, requireUser } from '../../../../lib/http'
import { updateDevice } from '../../../../lib/push'
import { RULES } from '../../../../lib/ratelimit'
import { pushDeviceIdSchema, pushDevicePatch } from '../../../../lib/schemas'

/** Kategorien, Vorschau, Name/Version/Sprache oder neuer UnifiedPush-Endpunkt (§33.2). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`pushManage:${auth.uuid}`, RULES.pushManageUser)
  const id = paramWith(event, 'id', pushDeviceIdSchema)
  const patch = await readJson(event, pushDevicePatch)
  return updateDevice(useCtx(), auth, id, patch)
})
