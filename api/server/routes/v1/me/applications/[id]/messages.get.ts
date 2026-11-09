import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { applicationMessages } from '../../../../../lib/application-chat'
import { useCtx } from '../../../../../lib/context'
import { paramWith, requireWebOrUser } from '../../../../../lib/http'

export default defineEventHandler((event) => {
  const me = requireWebOrUser(event)
  const id = paramWith(event, 'id', z.string().regex(/^a[0-9a-f]{16}$/))
  return { messages: applicationMessages(useCtx(), me.uuid, id) }
})