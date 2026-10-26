import { defineEventHandler, getQuery } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { notFound } from '../../../../lib/errors'
import { clientIp, limit, paramWith, sendCached } from '../../../../lib/http'
import { sha256Hex } from '../../../../lib/ids'
import { renderTitleSpec, verifyTitle } from '../../../../lib/mail/images'
import { RULES } from '../../../../lib/ratelimit'

/** Pixel-Überschrift einer Mail (§40.7): nur mit gültiger Signatur, ein Jahr cachebar. */
export default defineEventHandler((event) => {
  limit(`mailImg:${clientIp(event)}`, RULES.mailImageIp)
  const file = paramWith(event, 'file', z.string().regex(/^[0-9a-f]{24}\.png$/))
  const d = getQuery(event).d
  const spec = typeof d === 'string' ? verifyTitle(useCtx(), file.slice(0, 24), d) : null
  if (!spec) throw notFound()
  const png = renderTitleSpec(spec).png
  return sendCached(event, 'title.png', 'image/png', { body: png, sha256: sha256Hex(png), public: true })
})
