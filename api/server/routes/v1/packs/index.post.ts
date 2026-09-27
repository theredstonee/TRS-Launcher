import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { created, limit, queryWith, readLimited, requireUser } from '../../../lib/http'
import { assertPackType, PACK_DURATIONS, uploadPack } from '../../../lib/packs'
import { RULES } from '../../../lib/ratelimit'

/** Modpack teilen (§27): rohe `.mrpack`-Datei, Laufzeit per `?duration=1d|7d|30d|forever` → Code + Link. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`packUpload:${auth.uuid}`, RULES.packUploadUser)
  const q = queryWith(event, z.strictObject({ duration: z.enum(PACK_DURATIONS).default('7d') }))
  assertPackType(event)
  const ctx = useCtx()
  const body = await readLimited(event, ctx.config.packMaxBytes)
  return created(event, { pack: uploadPack(ctx, auth.uuid, body, q.duration) })
})
