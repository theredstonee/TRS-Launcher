import { defineEventHandler, setResponseHeader } from 'h3'
import { useCtx } from '../../../lib/context'
import { badgeList } from '../../../lib/halloween'
import { clientIp, limit } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'

/** `GET /v1/badges` (§36): alle Profil-Abzeichen mit Namen und Bild. Öffentlich, eine Stunde cachebar. */
export default defineEventHandler((event) => {
  limit(`halloween-public:${clientIp(event)}`, RULES.halloweenPublicIp)
  setResponseHeader(event, 'Cache-Control', 'public, max-age=3600')
  return badgeList(useCtx())
})
