import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { actingAs } from '../../../../lib/creator-http'
import { setCreatorPackListed } from '../../../../lib/creators'
import { limit, paramWith, readJson, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Eigenes geteiltes Modpack (§27) als Creator-Pack listen/entfernen (§35.3). Gelistet läuft es nicht ab. → `{ packs }` */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`creator-manage:${auth.uuid}`, RULES.creatorManageUser)
  const code = paramWith(event, 'packCode', z.string().min(1).max(32))
  return setCreatorPackListed(useCtx(), auth.uuid, code, await readJson(event, z.unknown()), actingAs(event))
})
