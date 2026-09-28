import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { readJson, requireUser } from '../../../../lib/http'
import { NOTES_BODY_LIMIT, notesCursor, notesPostBody, pushNotes } from '../../../../lib/notes-sync'

/** Notizen-Sync (§17.5): 1–50 Änderungen (letzter Schreiber gewinnt je Notiz) → Ergebnis je Eintrag. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'sync')
  const body = await readJson(event, notesPostBody, NOTES_BODY_LIMIT)
  const ctx = useCtx()
  const { results, changed } = pushNotes(ctx, auth.uuid, body.changes)
  // Eigene andere Geräte (Launcher, weitere Spiele) holen die Änderungen ab.
  if (changed) ctx.events.publish(auth.uuid, { type: 'notes_changed', cursor: notesCursor(ctx, auth.uuid) }, { meOnly: true })
  return { results }
})
