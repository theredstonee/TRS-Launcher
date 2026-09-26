import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { paramWith, readJson, requireAdmin } from '../../../../../lib/http'
import { adminAddNote } from '../../../../../lib/moderation'
import { adminNoteBody, reportIdSchema } from '../../../../../lib/schemas'

export default defineEventHandler(async (event) => {
  const actor = requireAdmin(event)
  const id = paramWith(event, 'id', reportIdSchema)
  const body = await readJson(event, adminNoteBody)
  return { report: adminAddNote(useCtx(), actor, id, body.text) }
})
