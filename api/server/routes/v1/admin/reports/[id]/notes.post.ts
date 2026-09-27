import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { adminAddNote, reportDetailFor } from '../../../../../lib/moderation'
import { can } from '../../../../../lib/team'
import { adminNoteBody, reportIdSchema } from '../../../../../lib/schemas'

export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'reports.handle')
  const actor = staff.uuid
  const id = paramWith(event, 'id', reportIdSchema)
  const body = await readJson(event, adminNoteBody)
  return { report: reportDetailFor(adminAddNote(useCtx(), actor, id, body.text), can(staff, 'reports.content')) }
})
