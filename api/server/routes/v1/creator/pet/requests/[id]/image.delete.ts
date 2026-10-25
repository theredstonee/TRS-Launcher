import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { actingAs } from '../../../../../../lib/creator-http'
import { deletePetRequestImage } from '../../../../../../lib/creator-pets'
import { limit, paramWith, requireWebOrUser } from '../../../../../../lib/http'
import { RULES } from '../../../../../../lib/ratelimit'

/** Referenzbild vom offenen Haustier-Wunsch entfernen (§35.13). → `{ request }` */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`creator-manage:${auth.uuid}`, RULES.creatorManageUser)
  const id = paramWith(event, 'id', z.coerce.number().int().min(1).max(Number.MAX_SAFE_INTEGER))
  return deletePetRequestImage(useCtx(), auth.uuid, id, actingAs(event))
})
