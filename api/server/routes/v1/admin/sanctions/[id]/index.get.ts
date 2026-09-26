import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { notFound } from '../../../../../lib/errors'
import { paramWith, requireStaff } from '../../../../../lib/http'
import { adminSanctionView, getSanction } from '../../../../../lib/sanctions'
import { sanctionIdSchema } from '../../../../../lib/schemas'

export default defineEventHandler((event) => {
  requireStaff(event)
  const ctx = useCtx()
  const s = getSanction(ctx, paramWith(event, 'id', sanctionIdSchema))
  if (!s) throw notFound('sanction_not_found', 'Sanction not found')
  return { sanction: adminSanctionView(ctx, s) }
})
