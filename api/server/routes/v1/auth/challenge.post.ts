import { defineEventHandler } from 'h3'
import { createChallenge } from '../../../lib/auth'
import { useCtx } from '../../../lib/context'
import { clientIp, created, limit, readJson } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { z } from 'zod'

export default defineEventHandler(async (event) => {
  limit(`challenge:${clientIp(event)}`, RULES.challengeIp)
  await readJson(event, z.strictObject({}).optional())
  return created(event, createChallenge(useCtx()))
})
