import { defineEventHandler, getHeader } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingLogs, logsQuery } from '../../../../lib/craftinghosting'
import { queryWith, requireWebOrUser } from '../../../../lib/http'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const q = queryWith(event, logsQuery)
  const lang = (getHeader(event, 'accept-language') ?? '').trim().slice(0, 2).toLowerCase()
  return { lines: await craftingLogs(useCtx(), auth.user, q.lines, lang) }
})
