import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { limit, requireUser } from '../../../lib/http'
import { listOwnPacks } from '../../../lib/packs'
import { RULES } from '../../../lib/ratelimit'

/** Eigene geteilte Modpacks + Grenzen (§27). */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'read')
  limit(`packManage:${auth.uuid}`, RULES.packManageUser)
  return listOwnPacks(useCtx(), auth.uuid)
})
