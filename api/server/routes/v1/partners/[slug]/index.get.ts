import { defineEventHandler, getRouterParam, setResponseHeader } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { notFound } from '../../../../lib/errors'
import { clientIp, limit } from '../../../../lib/http'
import { PARTNER_SLUG, publicPartner } from '../../../../lib/partners'
import { RULES } from '../../../../lib/ratelimit'

/** Ein Partner (§44.3) oder 404 `partner_not_found`. */
export default defineEventHandler((event) => {
  limit(`partnersPublic:${clientIp(event)}`, RULES.partnersPublicIp)
  const slug = z.string().regex(PARTNER_SLUG).safeParse(getRouterParam(event, 'slug', { decode: true }))
  if (!slug.success) throw notFound('partner_not_found', 'Partner not found')
  setResponseHeader(event, 'Cache-Control', 'public, max-age=60')
  return publicPartner(useCtx(), slug.data)
})
