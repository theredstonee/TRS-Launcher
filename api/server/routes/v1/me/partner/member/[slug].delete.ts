import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { limit, noContent, paramWith, requireWebOrUser } from '../../../../../lib/http'
import { leavePartner, PARTNER_SLUG } from '../../../../../lib/partners'
import { RULES } from '../../../../../lib/ratelimit'

/** Als Mitglied austreten. 204. */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`partner-write:${auth.uuid}`, RULES.partnerWriteUser)
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  leavePartner(useCtx(), auth.uuid, slug)
  return noContent(event)
})
