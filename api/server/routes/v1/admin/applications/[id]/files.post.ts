import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { releaseApplicationFile } from '../../../../../lib/application-chat'
import { useCtx } from '../../../../../lib/context'
import { paramWith, readJson, requireStaff } from '../../../../../lib/http'

export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'applications.review')
  const id = paramWith(event, 'id', z.string().regex(/^a[0-9a-f]{16}$/))
  const { fileId } = await readJson(event, z.strictObject({ fileId: z.string().regex(/^f[0-9a-f]{24}$/) }))
  return { file: releaseApplicationFile(useCtx(), staff, id, fileId) }
})