import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { limit, noContent, readJson, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { approveWebLogin } from '../../../lib/weblogin'

const Body = z.object({ code: z.string().min(1).max(20) }).strict()

/** TRS Launcher: Anmeldecode der Website bestätigen (nur Admins). 204 bei Erfolg. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`weblogin-approve:${auth.uuid}`, RULES.webLoginApproveUser)
  const { code } = await readJson(event, Body)
  approveWebLogin(useCtx(), auth, code)
  return noContent(event)
})
