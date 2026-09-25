import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { requireUser } from '../../../../lib/http'
import { syncOverview } from '../../../../lib/sync'

/** TRS-Sync: eigene Skins (ohne Bilder), Grabsteine der letzten 30 Tage, Presets und Einstellungen. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'sync')
  return syncOverview(useCtx(), auth.uuid)
})
