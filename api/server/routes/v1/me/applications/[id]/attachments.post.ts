import { defineEventHandler, getHeader } from 'h3'
import { z } from 'zod'
import { MAX_APPLICATION_FILE_BYTES, uploadApplicationFile } from '../../../../../lib/application-chat'
import { useCtx } from '../../../../../lib/context'
import { created, paramWith, readLimited, requireWebOrUser } from '../../../../../lib/http'

export default defineEventHandler(async (event) => {
  const me = requireWebOrUser(event, 'write')
  const id = paramWith(event, 'id', z.string().regex(/^a[0-9a-f]{16}$/))
  const encodedName = getHeader(event, 'x-file-name')
  if (!encodedName || encodedName.length > 720) throw createError({ statusCode: 400, statusMessage: 'File name required' })
  let name: string
  try {
    name = decodeURIComponent(encodedName)
  } catch {
    throw createError({ statusCode: 400, statusMessage: 'Invalid file name' })
  }
  const data = await readLimited(event, MAX_APPLICATION_FILE_BYTES)
  return created(event, { file: uploadApplicationFile(useCtx(), me.uuid, id, name, data) })
})