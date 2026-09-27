import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { limit, paramWith, readJson, requireUser } from '../../../lib/http'
import { PACK_DURATIONS, setPackDuration } from '../../../lib/packs'
import { RULES } from '../../../lib/ratelimit'

/** Laufzeit eines eigenen Packs ändern (zählt ab jetzt neu). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`packManage:${auth.uuid}`, RULES.packManageUser)
  const id = paramWith(event, 'id', z.string().max(64))
  const body = await readJson(event, z.strictObject({ duration: z.enum(PACK_DURATIONS) }))
  return { pack: setPackDuration(useCtx(), auth.uuid, id, body.duration) }
})
