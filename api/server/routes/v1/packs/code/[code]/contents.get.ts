import { defineEventHandler, setResponseHeaders } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { notFound } from '../../../../../lib/errors'
import { clientIp, limit, optionalUser, paramWith } from '../../../../../lib/http'
import { packByCode, packContents } from '../../../../../lib/packs'
import { RULES } from '../../../../../lib/ratelimit'

/**
 * Mods, Resource Packs und Shader eines geteilten Packs (§27.6) – öffentlich für die Website-Vorschau `/p/<code>`.
 * Nur Dateinamen und Modrinth-Projekt-IDs, keine Downloads; zählt nicht als Installation.
 */
export default defineEventHandler(async (event) => {
  const user = optionalUser(event)
  if (user) limit(`packLookup:${user.uuid}`, RULES.packLookupUser)
  else limit(`packPublic:${clientIp(event)}`, RULES.packPublicIp)
  const code = paramWith(event, 'code', z.string().max(32))
  const ctx = useCtx()
  const p = packByCode(ctx, code)
  if (!p) throw notFound('pack_not_found', 'No modpack with this code (wrong code, expired or deleted)')
  setResponseHeaders(event, { 'Cache-Control': 'private, no-store', 'X-Robots-Tag': 'noindex, nofollow' })
  return { contents: await packContents(ctx, p) }
})
