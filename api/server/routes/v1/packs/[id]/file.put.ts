import { defineEventHandler, getHeader } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { limit, paramWith, readJson, readLimited, requireUser } from '../../../../lib/http'
import { assertPackType, updatePackFile, updatePackFromToken } from '../../../../lib/packs'
import { PACK_SINGLE_MAX_BYTES, PACK_UPLOAD_TOKEN } from '../../../../lib/packupload'
import { RULES } from '../../../../lib/ratelimit'

const tokenBody = z.strictObject({ uploadToken: z.string().regex(PACK_UPLOAD_TOKEN) })

/** Neue Version eines eigenen Packs (Rohdatei bis 100 MB oder `uploadToken`). Gleicher Code, `revision` + 1. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`packUpload:${auth.uuid}`, RULES.packUploadUser)
  const id = paramWith(event, 'id', z.string().max(64))
  const ctx = useCtx()
  const type = (getHeader(event, 'content-type') ?? '').split(';')[0]!.trim().toLowerCase()
  if (type === 'application/json') {
    const body = await readJson(event, tokenBody)
    return { pack: updatePackFromToken(ctx, auth.uuid, id, body.uploadToken) }
  }
  assertPackType(event)
  const raw = await readLimited(event, Math.min(ctx.config.packMaxBytes, PACK_SINGLE_MAX_BYTES))
  return { pack: updatePackFile(ctx, auth.uuid, id, raw) }
})
