import { defineEventHandler, getHeader, setResponseHeaders, setResponseStatus } from 'h3'
import { getAttachment, readAttachment } from '../../../../lib/attachments'
import { accessMessage } from '../../../../lib/chat'
import { useCtx } from '../../../../lib/context'
import { notFound } from '../../../../lib/errors'
import { limit, paramWith, queryWith, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { attachmentIdSchema, attachmentQuery } from '../../../../lib/schemas'

/**
 * Bild bzw. Vorschau (`?thumb=1`). Nur für den Hochlader und Mitglieder der Unterhaltung, die die
 * Nachricht sehen dürfen; sonst 404. Inhalt ändert sich nie → privat lange cachebar.
 */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  limit(`chatImage:${auth.uuid}`, RULES.chatImageUser)
  const id = paramWith(event, 'id', attachmentIdSchema)
  const q = queryWith(event, attachmentQuery)
  const ctx = useCtx()
  const a = getAttachment(ctx, id)
  if (!a) throw notFound('attachment_not_found', 'Image not found')
  if (a.uploader_uuid !== auth.uuid) {
    if (!a.message_id) throw notFound('attachment_not_found', 'Image not found')
    try {
      const { msg } = accessMessage(ctx, auth.uuid, a.message_id)
      if (msg.deleted_at !== null) throw new Error('deleted')
    } catch {
      throw notFound('attachment_not_found', 'Image not found')
    }
  }
  const thumb = q.thumb === '1' || q.thumb === 'true'
  const etag = `"${a.sha256.slice(0, 32)}${thumb ? '-t' : ''}"`
  setResponseHeaders(event, {
    'Content-Type': thumb ? a.thumb_mime : a.mime,
    'Content-Disposition': `inline; filename="${a.id}${(thumb ? a.thumb_mime : a.mime) === 'image/png' ? '.png' : '.jpg'}"`,
    'Cache-Control': 'private, max-age=31536000, immutable',
    ETag: etag,
  })
  if (getHeader(event, 'if-none-match') === etag) {
    setResponseStatus(event, 304)
    return ''
  }
  try {
    return readAttachment(ctx, a, thumb)
  } catch (err) {
    console.error(`[trs-api] could not read attachment ${a.id}`, (err as Error).message)
    throw notFound('attachment_not_found', 'Image not found')
  }
})
