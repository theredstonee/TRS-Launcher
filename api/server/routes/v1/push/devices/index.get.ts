import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { requireUser } from '../../../../lib/http'
import { listDevices } from '../../../../lib/push'

/** Eigene Push-Geräte (§33.2). */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  return { devices: listDevices(useCtx(), auth.uuid, auth.tokenHash) }
})
