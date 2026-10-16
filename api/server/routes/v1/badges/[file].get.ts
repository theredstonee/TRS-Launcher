import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { isBadgeId } from '../../../../shared/halloween'
import { useCtx } from '../../../lib/context'
import { notFound } from '../../../lib/errors'
import { artKey } from '../../../lib/halloween'
import { clientIp, limit, paramWith, sendCached } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'

/** `GET /v1/badges/{id}.png` (§36): Abzeichen als Pixel-PNG (×8 nächster Nachbar). Öffentlich, mit `?v=` ein Jahr cachebar. */
export default defineEventHandler((event) => {
  const file = paramWith(event, 'file', z.string().regex(/^[a-z][a-z0-9_]{0,31}\.png$/))
  const id = file.slice(0, -4)
  limit(`halloween-public:${clientIp(event)}`, RULES.halloweenPublicIp)
  const art = isBadgeId(id) ? useCtx().halloweenArt.get(artKey('badges', id)) : undefined
  if (!art) throw notFound('badge_not_found', 'Badge not found')
  return sendCached(event, `${id}.png`, 'image/png', { body: art.png, sha256: art.sha256, public: true })
})
