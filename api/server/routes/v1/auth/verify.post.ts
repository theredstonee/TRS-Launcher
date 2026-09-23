import { defineEventHandler } from 'h3'
import { verifyLogin } from '../../../lib/auth'
import { useCtx } from '../../../lib/context'
import { clientIp, limit, readJson } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { verifyBody } from '../../../lib/schemas'

export default defineEventHandler(async (event) => {
  limit(`verify:${clientIp(event)}`, RULES.verifyIp)
  const body = await readJson(event, verifyBody)
  limit(`verify-name:${body.username.toLowerCase()}`, RULES.verifyName)
  return verifyLogin(useCtx(), body.username, body.serverId)
})
