import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { findPumpkin } from '../../../../../lib/halloween'
import { limit, paramWith, requireWebOrUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** `POST /v1/me/halloween/pumpkins/{id}` (§36): Kürbis gefunden. Idempotent, höchstens einer je 2 s. */
export default defineEventHandler((event) => {
  const me = requireWebOrUser(event, 'write')
  const id = paramWith(event, 'id', z.string().min(1).max(64))
  limit(`halloween-pumpkin:${me.uuid}`, RULES.halloweenPumpkinUser)
  return findPumpkin(useCtx(), me.uuid, id)
})
