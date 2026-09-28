import { defineEventHandler } from 'h3'
import { publicCatalog } from '../../../lib/achievements'
import { clientIp, limit } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'

/** Öffentlicher Erfolgs-Katalog (§31.2): geheime Erfolge ohne Text. */
export default defineEventHandler((event) => {
  limit(`achCatalog:${clientIp(event)}`, RULES.achievementCatalogIp)
  return publicCatalog()
})
