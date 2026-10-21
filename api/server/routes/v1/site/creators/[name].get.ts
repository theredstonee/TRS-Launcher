import { defineEventHandler, getRouterParam, setResponseHeader } from 'h3'
import { useCtx } from '../../../../lib/context'
import { siteCreator } from '../../../../lib/creators'
import { clientIp, limit } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/**
 * Ein Creator (§35.11): Spielername (ohne Groß/klein) oder aktiver Creator-Code. Unbekannt/inaktiv → 404
 * `creator_not_found`. 5 min cachebar.
 */
export default defineEventHandler((event) => {
  limit(`creatorsPublic:${clientIp(event)}`, RULES.creatorsPublicIp)
  const r = siteCreator(useCtx(), getRouterParam(event, 'name', { decode: true }) ?? '')
  setResponseHeader(event, 'Cache-Control', 'public, max-age=300')
  return r
})
