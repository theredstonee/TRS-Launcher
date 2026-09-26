import { defineEventHandler, getHeader } from 'h3'
import { uploadAttachment } from '../../../../lib/attachments'
import { useCtx } from '../../../../lib/context'
import { unsupportedMedia } from '../../../../lib/errors'
import { created, limit, readLimited, requireUser } from '../../../../lib/http'
import { MAX_CHAT_IMAGE_BYTES } from '../../../../lib/images'
import { RULES } from '../../../../lib/ratelimit'

const TYPES = new Set(['image/png', 'image/jpeg', 'image/webp'])

/** Bild hochladen (roh, PNG/JPEG/WebP ≤ 5 MiB) → neu kodiert, verschlüsselt; ID dann in einer Nachricht verwenden. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`chatUpload:${auth.uuid}`, RULES.chatUploadUser)
  const type = (getHeader(event, 'content-type') ?? '').split(';')[0]!.trim().toLowerCase()
  if (!TYPES.has(type)) throw unsupportedMedia('Content-Type must be image/png, image/jpeg or image/webp')
  const body = await readLimited(event, MAX_CHAT_IMAGE_BYTES)
  return created(event, { attachment: await uploadAttachment(useCtx(), auth.uuid, body, type) })
})
