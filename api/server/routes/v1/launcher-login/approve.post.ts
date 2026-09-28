import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { limit, noContent, readJson, requireUser } from '../../../lib/http'
import { decideLauncherLogin } from '../../../lib/launcherlogin'
import { RULES } from '../../../lib/ratelimit'

const body = z.object({ id: z.string().max(64), code: z.string().max(20) }).strict()

/**
 * Launcher: Anmelde-Anfrage bestätigen (§29.3) – nur nach einem Klick des Spielers. Der Browser, der die Anfrage
 * angelegt hat, wird danach mit DIESEM Konto angemeldet. Genau einmal; `code` = der im Dialog angezeigte Code.
 */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`llogin-decide:${auth.uuid}`, RULES.launcherLoginDecideUser)
  const { id, code } = await readJson(event, body)
  decideLauncherLogin(useCtx(), auth.uuid, id, code, true)
  return noContent(event)
})
