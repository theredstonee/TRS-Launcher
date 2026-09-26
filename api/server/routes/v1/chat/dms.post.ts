import { defineEventHandler } from 'h3'
import { openDm } from '../../../lib/chat'
import { useCtx } from '../../../lib/context'
import { limit, readJson, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { openDmBody } from '../../../lib/schemas'

/** DM mit einem Freund öffnen (oder die bestehende holen). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event)
  const body = await readJson(event, openDmBody)
  limit(`chatGroup:${auth.uuid}`, RULES.chatGroupUser)
  return { conversation: openDm(useCtx(), auth.uuid, body.uuid) }
})
