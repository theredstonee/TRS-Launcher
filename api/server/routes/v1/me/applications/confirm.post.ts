import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { confirmApplicationEmail } from '../../../../lib/applications'
import { useCtx } from '../../../../lib/context'
import { created, readJson, requireWebOrUser } from '../../../../lib/http'

export default defineEventHandler(async (event) => {
  const me = requireWebOrUser(event, 'write')
  const body = await readJson(event, z.strictObject({ token: z.string().regex(/^c[A-Za-z0-9_-]{43}$/) }), 4096)
  return created(event, { application: confirmApplicationEmail(useCtx(), me, body.token) })
})