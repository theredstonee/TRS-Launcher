import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { notFound } from '../../../../lib/errors'
import { limit, requireUser } from '../../../../lib/http'
import { isMiyako, setMiyakoIntroDone } from '../../../../lib/miyako'
import { RULES } from '../../../../lib/ratelimit'

/** `POST /v1/me/miyako/intro-done` (§36, privat, nur MiyakoMC): merkt, dass das einmalige Intro lief. Sonst 404. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  if (!isMiyako(auth.uuid)) throw notFound()
  limit(`miyako:${auth.uuid}`, RULES.miyakoUser)
  setMiyakoIntroDone(useCtx(), auth.uuid)
  return { introDone: true }
})
