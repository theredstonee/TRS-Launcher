import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { adminAddNote } from '../../../../../lib/moderation'
import { adminNoteBody, reportIdSchema } from '../../../../../lib/schemas'

export default defineEventHandler(async (event) => {
  const actor = requireStaff(event).uuid
  const id = paramWith(event, 'id', reportIdSchema)
  const body = await readJson(event, adminNoteBody)
  return { report: adminAddNote(useCtx(), actor, id, body.text) }
})
