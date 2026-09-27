import { defineEventHandler, setResponseHeaders } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { notFound } from '../../../lib/errors'
import { clientIp, limit, paramWith } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { getShare, publicShareView } from '../../../lib/shares'

/** Öffentlich (ohne Anmeldung): Daten eines geteilten Screenshots – ohne Besitzer. */
export default defineEventHandler((event) => {
  limit(`sharePublic:${clientIp(event)}`, RULES.sharePublicIp)
  const id = paramWith(event, 'id', z.string().max(64))
  const ctx = useCtx()
  const s = getShare(ctx, id)
  if (!s) throw notFound('share_not_found', 'Shared image not found')
  setResponseHeaders(event, {
    'Cache-Control': 'public, max-age=60',
    'X-Robots-Tag': 'noindex, nofollow',
  })
  return { share: publicShareView(ctx, s) }
})
