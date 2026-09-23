import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { block } from '../../../lib/friends'
import { created, limit, readJson, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { targetBody } from '../../../lib/schemas'

/** Blockieren: beendet Freundschaft + Anfragen in beide Richtungen; der andere kann uns nicht mehr anfragen und sieht unsere Kosmetik nicht. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`friends:${auth.uuid}`, RULES.friendsWriteUser)
  const body = await readJson(event, targetBody)
  return created(event, { blocked: block(useCtx(), auth.uuid, body.target) })
})
