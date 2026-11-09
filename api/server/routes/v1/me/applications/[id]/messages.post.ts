import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { sendApplicationMessage } from '../../../../../lib/application-chat'
import { useCtx } from '../../../../../lib/context'
import { created, paramWith, readJson, requireWebOrUser } from '../../../../../lib/http'

export default defineEventHandler(async (event) => {
  const me = requireWebOrUser(event, 'write')
  const id = paramWith(event, 'id', z.string().regex(/^a[0-9a-f]{16}$/))
  const body = await readJson(event, z.unknown(), 16 * 1024)
  return created(event, { message: sendApplicationMessage(useCtx(), me.uuid, id, body) })
})