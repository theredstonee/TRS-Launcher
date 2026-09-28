import { defineEventHandler, setResponseHeaders } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { notFound } from '../../../../lib/errors'
import { clientIp, limit, paramWith } from '../../../../lib/http'
import { MODRINTH_ID, modrinthIcon } from '../../../../lib/modrinth'
import { RULES } from '../../../../lib/ratelimit'

/**
 * Symbol eines Modrinth-Projekts aus einer Pack-Inhaltsliste (§27.6), über unseren Server geladen (Besucher verbinden
 * sich nicht mit Modrinth). Nur Projekte, die schon in einer Liste vorkamen; nur PNG/JPEG/GIF/WebP ≤ 512 KB.
 */
export default defineEventHandler(async (event) => {
  limit(`modrinthIcon:${clientIp(event)}`, RULES.modrinthIconIp)
  const id = paramWith(event, 'id', z.string().regex(MODRINTH_ID))
  const icon = await modrinthIcon(useCtx(), id)
  if (!icon) throw notFound('icon_not_found', 'No icon')
  setResponseHeaders(event, {
    'Content-Type': icon.type,
    'Cache-Control': 'public, max-age=86400',
    'X-Content-Type-Options': 'nosniff',
    'Content-Security-Policy': "default-src 'none'; sandbox",
  })
  return icon.data
})
