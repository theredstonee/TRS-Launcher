import { defineEventHandler } from 'h3'
import { approveCape } from '../../../../../lib/admin'
import { useCtx } from '../../../../../lib/context'
import { paramWith, requireStaff } from '../../../../../lib/http'
import { capeIdSchema } from '../../../../../lib/schemas'

export default defineEventHandler((event) => {
  const actor = requireStaff(event).uuid
  return { cape: approveCape(useCtx(), actor, paramWith(event, 'id', capeIdSchema)) }
})
