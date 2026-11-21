import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { paramWith, requireWebOrUser } from '../../../../lib/http'
import { PARTNER_SLUG, partnerDashboard } from '../../../../lib/partners'

/** Partner-Dashboard (§44.4): Inhaber, Mitglieder (je nach Rechten) oder Team-Modus. */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event)
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  return partnerDashboard(useCtx(), auth.uuid, slug)
})
