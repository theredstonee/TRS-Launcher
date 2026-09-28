import { defineEventHandler, getHeader } from 'h3'
import { countAchievement } from '../../../lib/achievements'
import { useCtx } from '../../../lib/context'
import { unsupportedMedia } from '../../../lib/errors'
import { created, limit, readLimited, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { MAX_SHARE_BYTES, uploadShare } from '../../../lib/shares'

const TYPES = new Set(['image/png', 'image/jpeg', 'image/webp'])

/** Screenshot als Link teilen (roh, PNG/JPEG/WebP ≤ 10 MiB) → neu kodiert, 30 Tage öffentlich unter `/s/<id>` (§23). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`shareUpload:${auth.uuid}`, RULES.shareUploadUser)
  const type = (getHeader(event, 'content-type') ?? '').split(';')[0]!.trim().toLowerCase()
  if (!TYPES.has(type)) throw unsupportedMedia('Content-Type must be image/png, image/jpeg or image/webp')
  const body = await readLimited(event, MAX_SHARE_BYTES)
  const ctx = useCtx()
  const share = await uploadShare(ctx, auth.uuid, body, type)
  countAchievement(ctx, auth.uuid, 'screenshots_shared')
  return created(event, { share })
})
