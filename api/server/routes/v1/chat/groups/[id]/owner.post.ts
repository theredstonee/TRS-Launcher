import { defineEventHandler } from 'h3'
import { transferOwner } from '../../../../../lib/chat'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { conversationIdSchema, transferOwnerBody } from '../../../../../lib/schemas'

/** Besitz an ein Mitglied übergeben. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const id = paramWith(event, 'id', conversationIdSchema)
  const body = await readJson(event, transferOwnerBody)
  limit(`chatGroup:${auth.uuid}`, RULES.chatGroupUser)
  return { conversation: transferOwner(useCtx(), auth.uuid, id, body.uuid) }
})
