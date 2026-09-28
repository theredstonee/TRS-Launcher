import { defineEventHandler, getHeader, setResponseHeaders, setResponseStatus } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { notFound } from '../../../../lib/errors'
import { paramWith, queryWith } from '../../../../lib/http'
import { publicIssueRead } from '../../../../lib/issue-http'
import { readUpload } from '../../../../lib/issues'
import { attachmentQuery } from '../../../../lib/schemas'

/** Bild eines Issues/Kommentars (`?thumb=1` = Vorschau). Öffentlich, solange Issue und Kommentar sichtbar sind. */
export default defineEventHandler((event) => {
  const viewer = publicIssueRead(event)
  const id = paramWith(event, 'id', z.string().max(64))
  const q = queryWith(event, attachmentQuery)
  const thumb = q.thumb === '1' || q.thumb === 'true'
  let img: ReturnType<typeof readUpload>
  try {
    img = readUpload(useCtx(), id, viewer, thumb)
  } catch (err) {
    if ((err as { code?: string }).code === 'ENOENT') {
      console.error(`[trs-api] issue image file missing ${id}`)
      throw notFound('upload_not_found', 'Image not found')
    }
    throw err
  }
  const mime = thumb ? img.row.thumb_mime : img.row.mime
  const etag = `"${img.row.sha256.slice(0, 32)}${thumb ? '-t' : ''}"`
  setResponseHeaders(event, {
    'Content-Type': mime,
    'Content-Disposition': `inline; filename="trs-issue-${img.row.id}${thumb ? '-thumb' : ''}.${mime === 'image/png' ? 'png' : 'jpg'}"`,
    'Cross-Origin-Resource-Policy': 'same-site',
    'Cache-Control': img.public ? 'public, max-age=600' : 'private, no-store',
    ETag: etag,
  })
  if (getHeader(event, 'if-none-match') === etag) {
    setResponseStatus(event, 304)
    return ''
  }
  return img.data
})
