import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { queryWith, requireUser } from '../../../../lib/http'
import { listNotes, notesQuery } from '../../../../lib/notes-sync'

/** Notizen-Sync (§17.5): Änderungen seit `since` (ohne = alles, inkl. Grabsteine), seitenweise. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'sync')
  const q = queryWith(event, notesQuery)
  return listNotes(useCtx(), auth.uuid, q)
})
