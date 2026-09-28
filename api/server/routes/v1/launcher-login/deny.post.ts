import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { limit, noContent, readJson, requireUser } from '../../../lib/http'
import { decideLauncherLogin } from '../../../lib/launcherlogin'
import { RULES } from '../../../lib/ratelimit'

const body = z.object({ id: z.string().max(64), code: z.string().max(20) }).strict()

/** Launcher: Anmelde-Anfrage ablehnen (§29.3). Die Website zeigt dann „abgelehnt“. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`llogin-decide:${auth.uuid}`, RULES.launcherLoginDecideUser)
  const { id, code } = await readJson(event, body)
  decideLauncherLogin(useCtx(), auth.uuid, id, code, false)
  return noContent(event)
})
