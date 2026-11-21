import { defineEventHandler, getHeader, getQuery } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { clientIp, limit, noContent, paramWith } from '../../../../lib/http'
import { countDownload, PARTNER_SLUG } from '../../../../lib/partners'
import { RULES } from '../../../../lib/ratelimit'

/** Download-Knopf nach einem Werbe-Link (§44.3): zählt einen Download für Partner/Kampagne (`?c=`). Immer 204. */
export default defineEventHandler((event) => {
  const ip = clientIp(event)
  limit(`partner-count:${ip}`, RULES.partnerCountIp)
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  countDownload(useCtx(), slug, getQuery(event).c, { ip, ua: (getHeader(event, 'user-agent') ?? '').slice(0, 300) })
  return noContent(event)
})
