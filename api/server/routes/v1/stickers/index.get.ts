import { defineEventHandler, setResponseHeader } from 'h3'
import { useCtx } from '../../../lib/context'
import { stickerList } from '../../../lib/halloween'
import { clientIp, limit } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'

/** `GET /v1/stickers` (§36): alle Chat-Sticker mit Namen und Bild. Öffentlich, eine Stunde cachebar. */
export default defineEventHandler((event) => {
  limit(`halloween-public:${clientIp(event)}`, RULES.halloweenPublicIp)
  setResponseHeader(event, 'Cache-Control', 'public, max-age=3600')
  return stickerList(useCtx())
})
