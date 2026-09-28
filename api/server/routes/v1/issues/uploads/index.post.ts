import { defineEventHandler, getHeader } from 'h3'
import { ISSUE_LIMITS } from '../../../../../shared/issues'
import { useCtx } from '../../../../lib/context'
import { unsupportedMedia } from '../../../../lib/errors'
import { created, readLimited } from '../../../../lib/http'
import { requireIssueWriter } from '../../../../lib/issue-http'
import { uploadIssueImage } from '../../../../lib/issues'

const TYPES = new Set(['image/png', 'image/jpeg', 'image/webp'])

/** Bild für ein Issue/einen Kommentar hochladen (roh, ≤ 8 MiB) → neu kodiert, 1 h gültig bis zum Anhängen (§28.3). */
export default defineEventHandler(async (event) => {
  const viewer = requireIssueWriter(event, 'issueUploadUser')
  const type = (getHeader(event, 'content-type') ?? '').split(';')[0]!.trim().toLowerCase()
  if (!TYPES.has(type)) throw unsupportedMedia('Content-Type must be image/png, image/jpeg or image/webp')
  const body = await readLimited(event, ISSUE_LIMITS.uploadMaxBytes)
  return created(event, { upload: await uploadIssueImage(useCtx(), viewer.uuid, body, type) })
})
