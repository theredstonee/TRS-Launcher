import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { limit, noContent, paramWith, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { deleteShare } from '../../../lib/shares'

/** Eigenen Link vor Ablauf löschen (Bild weg, Link zeigt „nicht gefunden“). */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`shareManage:${auth.uuid}`, RULES.shareManageUser)
  const id = paramWith(event, 'id', z.string().max(64))
  deleteShare(useCtx(), auth.uuid, id)
  return noContent(event)
})
