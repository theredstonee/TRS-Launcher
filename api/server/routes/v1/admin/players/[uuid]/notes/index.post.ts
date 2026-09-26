import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../../lib/context'
import { addPlayerNote } from '../../../../../../lib/dossier'
import { created, paramWith, readJson, requireStaff } from '../../../../../../lib/http'
import { playerNoteBody, uuidSchema } from '../../../../../../lib/schemas'

/** Interne Notiz zum Spieler (nur Team). */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  const body = await readJson(event, playerNoteBody)
  return created(event, { notes: addPlayerNote(useCtx(), staff, uuid, body.text) })
})
