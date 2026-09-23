import { defineEventHandler } from 'h3'
import { stats } from '../../../lib/admin'
import { useCtx } from '../../../lib/context'
import { requireAdmin } from '../../../lib/http'

export default defineEventHandler((event) => {
  requireAdmin(event)
  return stats(useCtx())
})
