import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { withdrawApplication } from '../../../../../lib/applications'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, requireWebOrUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Eigene, noch offene Bewerbung zurückziehen → `{ application }`. */
export default defineEventHandler((event) => {
  const me = requireWebOrUser(event, 'write')
  limit(`withdraw:${me.uuid}`, RULES.withdrawUser)
  const id = paramWith(event, 'id', z.string().regex(/^a[0-9a-f]{16}$/))
  return { application: withdrawApplication(useCtx(), me.uuid, id) }
})
