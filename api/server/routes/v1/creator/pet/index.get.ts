import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { actingAs } from '../../../../lib/creator-http'
import { creatorPetState } from '../../../../lib/creator-pets'
import { requireWebOrUser } from '../../../../lib/http'

/** Eigenes Creator-Haustier (§35.13): Haustier, Monatsgrenze, Codes (Recht `codes`), Wünsche (nur der Creator). */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event)
  return creatorPetState(useCtx(), auth.uuid, actingAs(event))
})
