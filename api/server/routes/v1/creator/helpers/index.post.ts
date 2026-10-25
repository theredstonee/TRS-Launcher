import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { actingAs } from '../../../../lib/creator-http'
import { inviteHelper } from '../../../../lib/creators'
import { created, limit, readJson, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Freund als Helfer einladen (§35.9): `{ uuid, rights }`. Nur der Creator selbst (auch nicht im Team-Modus). → 201 `{ helpers }` */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`creator-manage:${auth.uuid}`, RULES.creatorManageUser)
  return created(event, inviteHelper(useCtx(), auth.uuid, await readJson(event, z.unknown()), actingAs(event)))
})
