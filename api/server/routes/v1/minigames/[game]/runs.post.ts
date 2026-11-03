import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { limit, paramWith, readJson, requireUser } from '../../../../lib/http'
import { SUBMIT_BODY_MAX, gameSchema, submitRun } from '../../../../lib/minigames'
import { RULES } from '../../../../lib/ratelimit'

/** Partie einreichen (§42.2): `{ runId, inputs, score }` → `{ accepted, rank, best, improved }`. Der Server rechnet nach. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const game = paramWith(event, 'game', gameSchema)
  limit(`minigameSubmit:${auth.uuid}`, RULES.minigameSubmitUser)
  const body = await readJson(event, z.unknown(), SUBMIT_BODY_MAX)
  return submitRun(useCtx(), auth.uuid, game, body)
})
