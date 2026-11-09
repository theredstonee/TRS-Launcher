import { defineEventHandler, getRouterParam, setResponseHeader } from 'h3'
import { z } from 'zod'
import { readApplicationFile } from '../../../../../../lib/application-chat'
import { useCtx } from '../../../../../../lib/context'
import { paramWith, requireWebOrUser } from '../../../../../../lib/http'

export default defineEventHandler((event) => {
  const me = requireWebOrUser(event)
  const id = paramWith(event, 'id', z.string().regex(/^a[0-9a-f]{16}$/))
  const fileId = getRouterParam(event, 'fileId', { decode: true }) ?? ''
  const file = readApplicationFile(useCtx(), me.uuid, id, fileId)
  setResponseHeader(event, 'Content-Type', 'application/octet-stream')
  setResponseHeader(event, 'Content-Disposition', `attachment; filename="attachment"; filename*=UTF-8''${encodeURIComponent(file.name)}`)
  setResponseHeader(event, 'X-Content-Type-Options', 'nosniff')
  setResponseHeader(event, 'Cache-Control', 'private, no-store')
  return file.data
})