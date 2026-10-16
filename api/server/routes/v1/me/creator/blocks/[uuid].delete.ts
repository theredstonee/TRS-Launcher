import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { unblockCreator } from '../../../../../lib/creators'
import { limit, paramWith, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { uuidSchema } from '../../../../../lib/schemas'

/** Blockade eines Creators aufheben (§35.2). → `{ blocked }` */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`creator-fan:${auth.uuid}`, RULES.creatorFanUser)
  return unblockCreator(useCtx(), auth.uuid, paramWith(event, 'uuid', uuidSchema))
})
