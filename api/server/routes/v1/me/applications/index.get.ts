import { defineEventHandler } from 'h3'
import { myApplications, pendingApplicationConfirmations } from '../../../../lib/applications'
import { useCtx } from '../../../../lib/context'
import { requireWebOrUser } from '../../../../lib/http'

/** Eigene Bewerbungen mit Status und Antwort des Teams (Website oder Launcher). */
export default defineEventHandler((event) => {
  const me = requireWebOrUser(event)
  const ctx = useCtx()
  return {
    applications: myApplications(ctx, me.uuid),
    pendingConfirmations: pendingApplicationConfirmations(ctx, me.uuid, import.meta.dev && process.env.TRS_DEV_LOGIN === '1'),
  }
})
