import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingLogs, logsQuery } from '../../../../lib/craftinghosting'
import { queryWith, requireUser } from '../../../../lib/http'

export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'craftinghosting')
  const q = queryWith(event, logsQuery)
  return { lines: await craftingLogs(useCtx(), auth.user, q.lines) }
})
