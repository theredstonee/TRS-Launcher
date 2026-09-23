import { defineEventHandler, setResponseStatus } from 'h3'
import { useCtx } from '../../../lib/context'
import { sendRequest } from '../../../lib/friends'
import { limit, readJson, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { targetBody } from '../../../lib/schemas'

/** Anfrage per Name oder UUID. Hatte das Gegenüber uns schon angefragt, sind beide sofort Freunde. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`friends:${auth.uuid}`, RULES.friendsWriteUser)
  const body = await readJson(event, targetBody)
  const result = sendRequest(useCtx(), auth.user, body.target)
  setResponseStatus(event, result.status === 'sent' ? 201 : 200)
  return result
})
