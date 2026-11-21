import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireWebOrUser } from '../../../../../lib/http'
import { answerInvite, PARTNER_SLUG } from '../../../../../lib/partners'
import { RULES } from '../../../../../lib/ratelimit'

/** Einladung ins Partner-Dashboard annehmen/ablehnen: `{ accept }` → wie `GET /v1/me/partner`. */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`partner-write:${auth.uuid}`, RULES.partnerWriteUser)
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  return answerInvite(useCtx(), auth.uuid, slug, await readJson(event, z.unknown()))
})
