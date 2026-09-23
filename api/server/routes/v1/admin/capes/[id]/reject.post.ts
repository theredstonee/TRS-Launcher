import { defineEventHandler } from 'h3'
import { rejectCape } from '../../../../../lib/admin'
import { useCtx } from '../../../../../lib/context'
import { paramWith, readJson, requireAdmin } from '../../../../../lib/http'
import { capeIdSchema, reasonBody } from '../../../../../lib/schemas'

export default defineEventHandler(async (event) => {
  const actor = requireAdmin(event)
  const id = paramWith(event, 'id', capeIdSchema)
  const body = await readJson(event, reasonBody)
  return { cape: rejectCape(useCtx(), actor, id, body?.reason) }
})
