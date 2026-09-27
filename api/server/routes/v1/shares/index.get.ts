import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { limit, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { listShares } from '../../../lib/shares'

/** Eigene geteilte Screenshots (aktive, neueste zuerst) + Grenzen (§23). */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  limit(`shareManage:${auth.uuid}`, RULES.shareManageUser)
  return listShares(useCtx(), auth.uuid)
})
