import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { listBans, requireHosting } from '../../../../lib/hosting'
import { requireUser } from '../../../../lib/http'

/** Dauerhafte Sperrliste des Hosts. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'hosting')
  const ctx = useCtx()
  requireHosting(ctx)
  return listBans(ctx, auth.uuid)
})
