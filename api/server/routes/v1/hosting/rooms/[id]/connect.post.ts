import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { connect } from '../../../../../lib/hosting'
import { limit, paramWith, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { roomIdSchema } from '../../../../../lib/schemas'

/** Frisches Relay-Token (≤ 2 min gültig zum Verbinden) + STUN-Liste. Host oder angenommener Gast. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'hosting')
  const id = paramWith(event, 'id', roomIdSchema)
  limit(`hostingConnect:${auth.uuid}`, RULES.hostingConnectUser)
  return connect(useCtx(), auth.uuid, id)
})
