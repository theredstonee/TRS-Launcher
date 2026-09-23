import { defineEventHandler, getHeader, getQuery, setResponseHeaders, setResponseStatus } from 'h3'
import { capeView, getCape, readTexture } from '../../../lib/capes'
import { useCtx } from '../../../lib/context'
import { notFound } from '../../../lib/errors'
import { CAPE_ID } from '../../../lib/ids'
import { optionalUser, paramWith } from '../../../lib/http'
import { z } from 'zod'

/**
 * `GET /v1/capes/{id}.png` → Textur (öffentlich nur freigegebene; wartende nur für Besitzer/Admins).
 * `GET /v1/capes/{id}`     → Metadaten (Frames, Frame-Dauer, URL).
 */
export default defineEventHandler((event) => {
  const raw = paramWith(event, 'id', z.string().max(48))
  const isPng = raw.endsWith('.png')
  const id = isPng ? raw.slice(0, -4) : raw
  if (!CAPE_ID.test(id)) throw notFound('cape_not_found', 'Cape not found')
  const ctx = useCtx()
  const viewer = optionalUser(event)

  if (!isPng) {
    const c = getCape(ctx, id)
    const visible = c && (c.status === 'approved' || (viewer && (viewer.admin || viewer.uuid === c.owner_uuid)))
    if (!c || !visible) throw notFound('cape_not_found', 'Cape not found')
    return { cape: capeView(ctx, c) }
  }

  const tex = readTexture(ctx, id, viewer ? { uuid: viewer.uuid, admin: viewer.admin } : null)
  const etag = `"${tex.sha256}"`
  const v = getQuery(event).v
  const current = typeof v !== 'string' || tex.sha256.startsWith(v)
  setResponseHeaders(event, {
    'Content-Type': 'image/png',
    'Content-Disposition': `inline; filename="${id}.png"`,
    'Cross-Origin-Resource-Policy': 'cross-origin',
    ETag: etag,
    'Cache-Control': !tex.public
      ? 'private, no-store'
      : current
        ? 'public, max-age=31536000, immutable'
        : 'public, max-age=300',
  })
  if (getHeader(event, 'if-none-match') === etag) {
    setResponseStatus(event, 304)
    return ''
  }
  return tex.png
})
