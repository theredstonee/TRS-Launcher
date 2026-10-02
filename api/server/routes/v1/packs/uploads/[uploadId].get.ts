import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { limit, paramWith, requireUser } from '../../../../lib/http'
import { packUploadStatus } from '../../../../lib/packupload'
import { RULES } from '../../../../lib/ratelimit'

const id = z.string().regex(/^[A-Za-z0-9_-]{22}$/)

/** Welche Stücke schon da sind (§27.7), zum Fortsetzen. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'read')
  limit(`packManage:${auth.uuid}`, RULES.packManageUser)
  const uploadId = paramWith(event, 'uploadId', id)
  return packUploadStatus(useCtx(), auth.uuid, uploadId)
})
