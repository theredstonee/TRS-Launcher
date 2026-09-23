import { defineEventHandler, getHeader } from 'h3'
import { authenticate } from '../../../lib/auth'
import { useCtx } from '../../../lib/context'
import { limit, readJson } from '../../../lib/http'
import { lookupPlayers } from '../../../lib/lookup'
import { RULES } from '../../../lib/ratelimit'
import { lookupBody } from '../../../lib/schemas'

/** Batch-Abfrage (≤100 UUIDs) für Abzeichen + Umhänge im Spiel. */
export default defineEventHandler(async (event) => {
  const ctx = useCtx()
  const auth = authenticate(ctx, getHeader(event, 'authorization'))
  limit(`lookup:${auth.uuid}`, RULES.lookupUser)
  const body = await readJson(event, lookupBody)
  return lookupPlayers(ctx, auth.uuid, body.uuids)
})
