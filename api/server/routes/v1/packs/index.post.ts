import { defineEventHandler, getHeader } from 'h3'
import { z } from 'zod'
import { countAchievement } from '../../../lib/achievements'
import { useCtx } from '../../../lib/context'
import { created, limit, queryWith, readJson, readLimited, requireUser } from '../../../lib/http'
import { assertPackType, PACK_DURATIONS, uploadPack, uploadPackFromToken } from '../../../lib/packs'
import { PACK_SINGLE_MAX_BYTES, PACK_UPLOAD_TOKEN } from '../../../lib/packupload'
import { RULES } from '../../../lib/ratelimit'

const tokenBody = z.strictObject({ uploadToken: z.string().regex(PACK_UPLOAD_TOKEN) })

/**
 * Modpack teilen (§27): rohe `.mrpack` (bis 100 MB, alte Launcher) oder `{ uploadToken }` aus dem Stück-Upload.
 * Laufzeit per `?duration=1d|7d|30d|forever` → Code + Link.
 */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`packUpload:${auth.uuid}`, RULES.packUploadUser)
  const q = queryWith(event, z.strictObject({ duration: z.enum(PACK_DURATIONS).default('7d') }))
  const ctx = useCtx()
  const type = (getHeader(event, 'content-type') ?? '').split(';')[0]!.trim().toLowerCase()
  let pack
  if (type === 'application/json') {
    const body = await readJson(event, tokenBody)
    pack = uploadPackFromToken(ctx, auth.uuid, body.uploadToken, q.duration)
  } else {
    assertPackType(event)
    const raw = await readLimited(event, Math.min(ctx.config.packMaxBytes, PACK_SINGLE_MAX_BYTES))
    pack = uploadPack(ctx, auth.uuid, raw, q.duration)
  }
  countAchievement(ctx, auth.uuid, 'packs_shared')
  return created(event, { pack })
})
