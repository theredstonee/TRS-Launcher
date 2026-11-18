import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingChangeVersion, versionBody } from '../../../../lib/craftinghosting'
import { limit, readJson, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Version/Servertyp wechseln (§43.13, nur mit Funktions-Schalter). */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const body = await readJson(event, versionBody)
  limit(`craftinghostingCreate:${auth.uuid}`, RULES.craftinghostingCreateUser)
  return { server: await craftingChangeVersion(useCtx(), auth.user, body) }
})
