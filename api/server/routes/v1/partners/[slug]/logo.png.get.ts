import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { clientIp, limit, paramWith, sendPng } from '../../../../lib/http'
import { PARTNER_SLUG, readLogo } from '../../../../lib/partners'
import { RULES } from '../../../../lib/ratelimit'

/** Partner-Logo 128×128 (§44.3); mit passendem `?v=` ein Jahr `immutable`. */
export default defineEventHandler((event) => {
  limit(`partnersPublic:${clientIp(event)}`, RULES.partnersPublicIp)
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  return sendPng(event, `${slug}.png`, readLogo(useCtx(), slug))
})
