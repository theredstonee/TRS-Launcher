import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { limit, noContent, paramWith } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { unpair } from '../../../../lib/remote'
import { requireDevice } from '../../../../lib/remote-http'

/** Kopplung lösen (§34.3) – am PC ein Handy entfernen oder am Handy einen PC. */
export default defineEventHandler((event) => {
  const { auth, device } = requireDevice(event, 'write')
  limit(`remoteManage:${auth.uuid}`, RULES.remoteManageUser)
  const peerId = paramWith(event, 'peerId', z.string().max(64))
  unpair(useCtx(), device, peerId)
  return noContent(event)
})
