import { defineEventHandler } from 'h3'
import { approveCape } from '../../../../../lib/admin'
import { useCtx } from '../../../../../lib/context'
import { paramWith, requireAdmin } from '../../../../../lib/http'
import { capeIdSchema } from '../../../../../lib/schemas'

export default defineEventHandler((event) => {
  const actor = requireAdmin(event)
  return { cape: approveCape(useCtx(), actor, paramWith(event, 'id', capeIdSchema)) }
})
