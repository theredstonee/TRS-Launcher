import { getHeader, setResponseHeaders, setResponseStatus, type H3Event } from 'h3'
import { z } from 'zod'
import { useCtx } from './context'
import { notFound } from './errors'
import { FEATURED_ID, featuredImage } from './featured'
import { clientIp, limit, paramWith } from './http'
import { RULES } from './ratelimit'

/** Symbol/Banner eines empfohlenen Modpacks ausliefern (§37.1): ETag aus SHA-256, einen Tag cachebar, `nosniff`. */
export function serveFeaturedImage(event: H3Event, kind: 'icon' | 'banner'): Buffer | string {
  limit(`featuredPublic:${clientIp(event)}`, RULES.featuredPublicIp)
  const id = paramWith(event, 'id', z.string().regex(FEATURED_ID))
  const img = featuredImage(useCtx(), id, kind)
  if (!img) throw notFound('not_found', 'No image')
  const etag = `"${img.sha256.slice(0, 32)}"`
  const ext = img.mime.slice('image/'.length).replace('jpeg', 'jpg')
  setResponseHeaders(event, {
    'Content-Type': img.mime,
    'Content-Disposition': `inline; filename="${id}-${kind}.${ext}"`,
    'Cache-Control': 'public, max-age=86400',
    'Cross-Origin-Resource-Policy': 'cross-origin',
    'X-Content-Type-Options': 'nosniff',
    'Content-Security-Policy': "default-src 'none'; sandbox",
    ETag: etag,
  })
  if (getHeader(event, 'if-none-match') === etag) {
    setResponseStatus(event, 304)
    return ''
  }
  return img.data
}
