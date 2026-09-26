import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { myInvites } from '../../../lib/hosting'
import { requireUser } from '../../../lib/http'

/** Offene Einladungen an mich (z. B. nach resync). */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'hosting')
  return { rooms: myInvites(useCtx(), auth.uuid) }
})
