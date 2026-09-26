import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../../lib/context'
import { deletePlayerNote } from '../../../../../../lib/dossier'
import { paramWith, requireStaff } from '../../../../../../lib/http'
import { noteIdSchema, uuidSchema } from '../../../../../../lib/schemas'

/** Notiz löschen: eigene oder (Admins) alle. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  const id = paramWith(event, 'id', noteIdSchema)
  return { notes: deletePlayerNote(useCtx(), staff, uuid, id) }
})
