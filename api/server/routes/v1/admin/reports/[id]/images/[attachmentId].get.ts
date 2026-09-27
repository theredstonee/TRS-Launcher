import { defineEventHandler, setResponseHeaders } from 'h3'
import { readEvidenceFile } from '../../../../../../lib/attachments'
import { useCtx } from '../../../../../../lib/context'
import { notFound } from '../../../../../../lib/errors'
import { paramWith, requireStaff } from '../../../../../../lib/http'
import { evidenceImageIdSchema, reportIdSchema } from '../../../../../../lib/schemas'

/** Aufbewahrte Kopie eines gemeldeten Bilds bzw. geteilten Screenshots (nur Team, auch nach dem Löschen). */
export default defineEventHandler((event) => {
  requireStaff(event, 'reports.content')
  const id = paramWith(event, 'id', reportIdSchema)
  const attachmentId = paramWith(event, 'attachmentId', evidenceImageIdSchema)
  const f = readEvidenceFile(useCtx(), id, attachmentId)
  if (!f) throw notFound('image_not_found', 'Image not found')
  setResponseHeaders(event, {
    'Content-Type': f.mime,
    'Content-Disposition': 'inline',
    'Cache-Control': 'private, no-store',
  })
  return f.data
})
