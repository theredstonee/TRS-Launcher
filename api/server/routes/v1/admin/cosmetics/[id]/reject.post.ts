import { defineEventHandler } from 'h3'
import { rejectCosmetic } from '../../../../../lib/admin'
import { useCtx } from '../../../../../lib/context'
import { paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { cosmeticIdSchema, reasonBody } from '../../../../../lib/schemas'

export default defineEventHandler(async (event) => {
  const actor = requireStaff(event).uuid
  const id = paramWith(event, 'id', cosmeticIdSchema)
  const body = await readJson(event, reasonBody)
  return { cosmetic: rejectCosmetic(useCtx(), actor, id, body?.reason) }
})
