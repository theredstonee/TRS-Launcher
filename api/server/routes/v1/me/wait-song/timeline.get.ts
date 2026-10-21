import { defineEventHandler, setResponseHeader } from 'h3'
import type { WaitTimeline } from '../../../../../shared/wait-song'
import { useCtx } from '../../../../lib/context'
import { requireWebOrUser } from '../../../../lib/http'
import { readWaitTimeline, waitSongAccess, waitSongFile, waitSongNotFound } from '../../../../lib/wait-song'

/**
 * `GET /v1/me/wait-song/timeline`: `{ audio, timeline }` – ob die MP3 da ist und das Zeitraster (`null`, wenn
 * timeline.json fehlt oder ungültig ist). Gleicher Zugriff wie die MP3 (sonst 404).
 */
export default defineEventHandler((event): { audio: boolean, timeline: WaitTimeline | null } => {
  const me = requireWebOrUser(event)
  const ctx = useCtx()
  if (!waitSongAccess(ctx, me.uuid)) throw waitSongNotFound()
  setResponseHeader(event, 'Cache-Control', 'private, no-store')
  return { audio: waitSongFile(ctx) !== null, timeline: readWaitTimeline(ctx) }
})
