import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { chooseWings } from '../../../../lib/halloween'
import { limit, readJson, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

const body = z.strictObject({ id: z.string().min(1).max(48) })

/** `POST /v1/me/halloween/wings` (§36): nach allen 13 Kürbissen genau einen der vier Halloween-Flügel wählen. */
export default defineEventHandler(async (event) => {
  const me = requireWebOrUser(event, 'write')
  limit(`halloween-wings:${me.uuid}`, RULES.halloweenTrickUser)
  const { id } = await readJson(event, body)
  return chooseWings(useCtx(), me.uuid, id)
})
