import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { limit, noContent, paramWith, requireUser } from '../../../lib/http'
import { deletePack } from '../../../lib/packs'
import { RULES } from '../../../lib/ratelimit'

/** Eigenes Pack löschen: Code und Link gelten sofort nicht mehr, Empfänger bekommen `pack_removed`. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`packManage:${auth.uuid}`, RULES.packManageUser)
  const id = paramWith(event, 'id', z.string().max(64))
  deletePack(useCtx(), auth.uuid, id)
  return noContent(event)
})
