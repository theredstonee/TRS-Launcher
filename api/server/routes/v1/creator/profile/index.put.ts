import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { actingAs } from '../../../../lib/creator-http'
import { submitProfile } from '../../../../lib/creators'
import { limit, readJson, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/**
 * Profil einreichen (§35.11, nur der Creator): `{ description, links }` → neue wartende Fassung für das Team. Öffentlich
 * bleibt bis zur Freigabe die bisherige. Antwort `{ approved, pending, rejected }`; Fehler 400 `invalid_profile`.
 */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`creator-manage:${auth.uuid}`, RULES.creatorManageUser)
  return submitProfile(useCtx(), auth.uuid, await readJson(event, z.unknown()), actingAs(event))
})
