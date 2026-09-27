import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { limit, noContent, paramWith, requireUser } from '../../../../lib/http'
import { dismissInbox } from '../../../../lib/packs'
import { RULES } from '../../../../lib/ratelimit'

/** Geschicktes Pack aus der Liste entfernen (das Pack selbst bleibt). */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`packManage:${auth.uuid}`, RULES.packManageUser)
  const id = paramWith(event, 'id', z.string().max(64))
  dismissInbox(useCtx(), auth.uuid, id)
  return noContent(event)
})
