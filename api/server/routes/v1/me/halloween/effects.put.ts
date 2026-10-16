import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { setEffects } from '../../../../lib/halloween'
import { limit, readJson, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { halloweenEffectsBody } from '../../../../lib/schemas'

/** `PUT /v1/me/halloween/effects` (§36): `{ hits, trail }` – dürfen andere TRS-Spieler meine Spuk-Effekte sehen? */
export default defineEventHandler(async (event) => {
  const me = requireWebOrUser(event, 'write')
  limit(`halloween-effects:${me.uuid}`, RULES.halloweenEffectsUser)
  const body = await readJson(event, halloweenEffectsBody)
  return { effects: setEffects(useCtx(), me.uuid, body) }
})
