import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { readJson, requireUser } from '../../../../lib/http'
import { syncPresetsBody } from '../../../../lib/schemas'
import { SYNC_PRESETS_BODY_LIMIT, clientTime, putDoc } from '../../../../lib/sync'

/** Eigene Presets speichern (letzter Schreiber gewinnt, sonst 409 `stale` mit `current`). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'sync')
  const body = await readJson(event, syncPresetsBody, SYNC_PRESETS_BODY_LIMIT)
  const ctx = useCtx()
  return { presets: putDoc(ctx, auth.uuid, 'presets', body.data, clientTime(ctx, body.updatedAt)) }
})
