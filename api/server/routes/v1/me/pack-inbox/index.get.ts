import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, requireUser } from '../../../../lib/http'
import { packInbox } from '../../../../lib/packs'
import { RULES } from '../../../../lib/ratelimit'

/** Modpacks, die Freunde dir geschickt haben (ohne ausgeblendete und abgelaufene). */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'read')
  limit(`packManage:${auth.uuid}`, RULES.packManageUser)
  return { packs: packInbox(useCtx(), auth.uuid) }
})
