import { defineEventHandler, setResponseHeaders } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { notFound } from '../../../../lib/errors'
import { clientIp, limit, optionalUser, paramWith } from '../../../../lib/http'
import { packByCode, packView } from '../../../../lib/packs'
import { RULES } from '../../../../lib/ratelimit'

/**
 * Pack zu einem Code (`TRS-XXXX-XXXX`, auch ohne Striche/klein). Öffentlich für die Website-Vorschau; mit Konto
 * (Launcher) zählt die Grenze je Konto statt je IP.
 */
export default defineEventHandler((event) => {
  const user = optionalUser(event)
  if (user) limit(`packLookup:${user.uuid}`, RULES.packLookupUser)
  else limit(`packPublic:${clientIp(event)}`, RULES.packPublicIp)
  const code = paramWith(event, 'code', z.string().max(32))
  const ctx = useCtx()
  const p = packByCode(ctx, code)
  if (!p) throw notFound('pack_not_found', 'No modpack with this code (wrong code, expired or deleted)')
  setResponseHeaders(event, { 'Cache-Control': 'private, no-store', 'X-Robots-Tag': 'noindex, nofollow' })
  return { pack: packView(ctx, p) }
})
