import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { readJson, requireUser } from '../../../../lib/http'
import { syncSettingsBody } from '../../../../lib/schemas'
import { clientTime, putDoc } from '../../../../lib/sync'

/** Theme, Akzent und Sprache speichern (nur diese drei Schlüssel; letzter Schreiber gewinnt). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'sync')
  const body = await readJson(event, syncSettingsBody)
  const ctx = useCtx()
  return { settings: putDoc(ctx, auth.uuid, 'settings', body.data, clientTime(ctx, body.updatedAt)) }
})
