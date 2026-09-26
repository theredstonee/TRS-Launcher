import { defineEventHandler } from 'h3'
import { renameGroup } from '../../../../../lib/chat'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { conversationIdSchema, renameGroupBody } from '../../../../../lib/schemas'

/** Gruppe umbenennen (nur Besitzer). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const id = paramWith(event, 'id', conversationIdSchema)
  const body = await readJson(event, renameGroupBody)
  limit(`chatGroup:${auth.uuid}`, RULES.chatGroupUser)
  return { conversation: renameGroup(useCtx(), auth.uuid, id, body.name) }
})
