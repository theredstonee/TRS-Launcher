import { defineEventHandler, setResponseHeader } from 'h3'
import { useCtx } from '../../../../lib/context'
import { siteCreators } from '../../../../lib/creators'
import { clientIp, limit } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/**
 * Öffentliche Creator-Liste (§35.11) für Website und Startseite: aktive Creator (Top → Partner → Starter, je Stufe meiste
 * Fans zuerst) mit freigegebenem Profil, Fan-Symbol, Fan-Umhang und Creator-Packs. 5 min cachebar.
 */
export default defineEventHandler((event) => {
  limit(`creatorsPublic:${clientIp(event)}`, RULES.creatorsPublicIp)
  setResponseHeader(event, 'Cache-Control', 'public, max-age=300')
  return siteCreators(useCtx())
})
