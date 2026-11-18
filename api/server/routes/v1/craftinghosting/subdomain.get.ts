import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { craftingCheckSubdomain, subdomainQuery } from '../../../lib/craftinghosting'
import { queryWith, requireUser } from '../../../lib/http'

/** Ist <name>.trs-launcher.de frei? (§43.9) */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'craftinghosting')
  const q = queryWith(event, subdomainQuery)
  return craftingCheckSubdomain(useCtx(), auth.uuid, q.name)
})
