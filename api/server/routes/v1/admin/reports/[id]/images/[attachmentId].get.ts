import { defineEventHandler, setResponseHeaders } from 'h3'
import { readEvidenceFile } from '../../../../../../lib/attachments'
import { useCtx } from '../../../../../../lib/context'
import { notFound } from '../../../../../../lib/errors'
import { paramWith, requireStaff } from '../../../../../../lib/http'
import { attachmentIdSchema, reportIdSchema } from '../../../../../../lib/schemas'

/** Aufbewahrte Kopie eines gemeldeten Bilds (nur Admins, auch nach dem Löschen der Nachricht). */
export default defineEventHandler((event) => {
  requireStaff(event)
  const id = paramWith(event, 'id', reportIdSchema)
  const attachmentId = paramWith(event, 'attachmentId', attachmentIdSchema)
  const f = readEvidenceFile(useCtx(), id, attachmentId)
  if (!f) throw notFound('image_not_found', 'Image not found')
  setResponseHeaders(event, {
    'Content-Type': f.mime,
    'Content-Disposition': 'inline',
    'Cache-Control': 'private, no-store',
  })
  return f.data
})
