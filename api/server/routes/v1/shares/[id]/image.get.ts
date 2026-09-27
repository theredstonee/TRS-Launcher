import { defineEventHandler, getHeader, setResponseHeaders, setResponseStatus } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { notFound } from '../../../../lib/errors'
import { clientIp, limit, paramWith, queryWith } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { attachmentQuery } from '../../../../lib/schemas'
import { getShare, readShareImage } from '../../../../lib/shares'

/**
 * Öffentlich: das geteilte Bild bzw. die Vorschau (`?thumb=1`). Darf in Vorschauen (Discord & Co.)
 * eingebettet werden (CORP cross-origin). Kurzer Cache, damit Löschen/Ablauf schnell greift.
 */
export default defineEventHandler((event) => {
  limit(`sharePublic:${clientIp(event)}`, RULES.sharePublicIp)
  const id = paramWith(event, 'id', z.string().max(64))
  const q = queryWith(event, attachmentQuery)
  const ctx = useCtx()
  const s = getShare(ctx, id)
  if (!s) throw notFound('share_not_found', 'Shared image not found')
  const thumb = q.thumb === '1' || q.thumb === 'true'
  const mime = thumb ? s.thumb_mime : s.mime
  const etag = `"${s.sha256.slice(0, 32)}${thumb ? '-t' : ''}"`
  setResponseHeaders(event, {
    'Content-Type': mime,
    'Content-Disposition': `inline; filename="trs-${s.id}${thumb ? '-thumb' : ''}.${mime === 'image/png' ? 'png' : 'jpg'}"`,
    'Cross-Origin-Resource-Policy': 'cross-origin',
    'Cache-Control': 'public, max-age=600',
    'X-Robots-Tag': 'noindex, nofollow',
    ETag: etag,
  })
  if (getHeader(event, 'if-none-match') === etag) {
    setResponseStatus(event, 304)
    return ''
  }
  try {
    return readShareImage(ctx, s, thumb)
  } catch (err) {
    console.error(`[trs-api] could not read shared image ${s.id}`, (err as Error).message)
    throw notFound('share_not_found', 'Shared image not found')
  }
})
