import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { trickOrTreat } from '../../../../lib/halloween'
import { limit, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** `POST /v1/me/halloween/trick` (§36): einmal je Berliner Kalendertag anklopfen – Süßes oder Saures. */
export default defineEventHandler((event) => {
  const me = requireWebOrUser(event, 'write')
  limit(`halloween-trick:${me.uuid}`, RULES.halloweenTrickUser)
  return trickOrTreat(useCtx(), me.uuid)
})
