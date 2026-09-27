import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { limit, paramWith, readLimited, requireUser } from '../../../../lib/http'
import { assertPackType, updatePackFile } from '../../../../lib/packs'
import { RULES } from '../../../../lib/ratelimit'

/** Neue Version eines eigenen Packs hochladen (gleicher Code, `revision` + 1, Empfänger bekommen `pack_updated`). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`packUpload:${auth.uuid}`, RULES.packUploadUser)
  const id = paramWith(event, 'id', z.string().max(64))
  assertPackType(event)
  const ctx = useCtx()
  const body = await readLimited(event, ctx.config.packMaxBytes)
  return { pack: updatePackFile(ctx, auth.uuid, id, body) }
})
