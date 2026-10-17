import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { notFound } from '../../../../lib/errors'
import { limit, requireUser, sendCached } from '../../../../lib/http'
import { isMiyako, miyakoBundle } from '../../../../lib/miyako'
import { RULES } from '../../../../lib/ratelimit'

/**
 * `GET /v1/me/miyako/bundle` (§36, privat, nur MiyakoMC): ZIP aus `frames/* + intro.ogg + jumpscare.png`, aus dem
 * privaten Asset-Ordner im Flug gebaut (ETag/304, nicht öffentlich cachebar). 404 für jedes andere Konto und 404,
 * wenn der private Ordner fehlt – der Start darf davon nie abhängen.
 */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  if (!isMiyako(auth.uuid)) throw notFound()
  limit(`miyako:${auth.uuid}`, RULES.miyakoUser)
  const zip = miyakoBundle(useCtx())
  if (!zip) throw notFound()
  return sendCached(event, 'miyako.zip', 'application/zip', { body: zip.buf, sha256: zip.sha256, public: false })
})
