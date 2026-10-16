import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { inviteHelper } from '../../../../lib/creators'
import { created, limit, readJson, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Freund als Helfer einladen (§35.9): `{ uuid, rights }`. Nur der Creator selbst. → 201 `{ helpers }` */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`creator-manage:${auth.uuid}`, RULES.creatorManageUser)
  return created(event, inviteHelper(useCtx(), auth.uuid, await readJson(event, z.unknown())))
})
