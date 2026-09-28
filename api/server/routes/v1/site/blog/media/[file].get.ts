import { defineEventHandler, getCookie, getHeader, setResponseHeaders, setResponseStatus, type H3Event } from 'h3'
import { z } from 'zod'
import { BLOG_MEDIA_FILE, canSeeDrafts, mediaForServe, readMedia } from '../../../../../lib/blog'
import { useCtx } from '../../../../../lib/context'
import { notFound } from '../../../../../lib/errors'
import { clientIp, limit, paramWith } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { teamOf } from '../../../../../lib/team'
import { WEB_SESSION_COOKIE, webSession } from '../../../../../lib/weblogin'

/** Team-Mitglied mit Blog-Recht (Website-Sitzung)? Dann auch Bilder von Entwürfen und geplanten Beiträgen. */
function blogStaff(event: H3Event): boolean {
  const cookie = getCookie(event, WEB_SESSION_COOKIE)
  if (!cookie) return false
  try {
    const ctx = useCtx()
    return canSeeDrafts(teamOf(ctx, webSession(ctx, cookie, undefined, false).uuid))
  } catch {
    return false
  }
}

/**
 * Bild eines Blog-Beitrags (§30.2): `<id>.<jpg|png>` bzw. Vorschau `<id>.t.<ext>`. Öffentlich, sobald der Beitrag
 * öffentlich ist (lange cachebar, Inhalt ändert sich nie); vorher nur für das Team (nie zwischengespeichert).
 */
export default defineEventHandler((event) => {
  limit(`blogMedia:${clientIp(event)}`, RULES.blogMediaIp)
  const file = paramWith(event, 'file', z.string().max(40).regex(BLOG_MEDIA_FILE))
  const [, id, thumb] = BLOG_MEDIA_FILE.exec(file)!
  const ctx = useCtx()
  const found = mediaForServe(ctx, id!)
  if (!found || (!found.public && !blogStaff(event))) throw notFound('media_not_found', 'No such image')
  const { row } = found
  const isThumb = thumb === '.t'
  const mime = isThumb ? row.thumb_mime : row.mime
  if (!file.endsWith(mime === 'image/png' ? '.png' : '.jpg')) throw notFound('media_not_found', 'No such image')
  const etag = `"${row.sha256.slice(0, 32)}${isThumb ? '-t' : ''}"`
  setResponseHeaders(event, {
    'Content-Type': mime,
    'Content-Disposition': `inline; filename="${file}"`,
    'Cross-Origin-Resource-Policy': 'cross-origin',
    'Cache-Control': found.public ? 'public, max-age=604800, immutable' : 'private, no-store',
    'X-Content-Type-Options': 'nosniff',
    ETag: etag,
  })
  if (getHeader(event, 'if-none-match') === etag) {
    setResponseStatus(event, 304)
    return ''
  }
  try {
    return readMedia(ctx, row, isThumb)
  } catch (err) {
    console.error(`[trs-api] could not read blog image ${row.id}`, (err as Error).message)
    throw notFound('media_not_found', 'No such image')
  }
})
