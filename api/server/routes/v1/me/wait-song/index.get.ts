import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { requireWebOrUser } from '../../../../lib/http'
import { sendWaitSong, waitSongAccess, waitSongFile, waitSongNotFound } from '../../../../lib/wait-song'

/**
 * `GET /v1/me/wait-song`: MP3 des persönlichen Musikvideos (gestreamt, `Range` → 206). Website-Sitzung oder Bearer.
 * Nur der Creator selbst und Owner/Admins – alle anderen bekommen 404 wie bei einer fehlenden Datei.
 */
export default defineEventHandler((event) => {
  const me = requireWebOrUser(event)
  const ctx = useCtx()
  if (!waitSongAccess(ctx, me.uuid)) throw waitSongNotFound()
  const file = waitSongFile(ctx)
  if (!file) throw waitSongNotFound()
  return sendWaitSong(event, file)
})
