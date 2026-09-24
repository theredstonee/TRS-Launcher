import { defineEventHandler, setResponseHeader } from 'h3'
import { latestRelease } from '../../../lib/site'

/** Website: neueste Launcher-Version mit Downloads (aus GitHub, zwischengespeichert). */
export default defineEventHandler(async (event) => {
  setResponseHeader(event, 'Cache-Control', 'public, max-age=300')
  return { release: await latestRelease().catch(() => null) }
})
