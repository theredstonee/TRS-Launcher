import { defineEventHandler } from 'h3'
import { stats } from '../../../lib/admin'
import { useCtx } from '../../../lib/context'
import { requireStaff } from '../../../lib/http'

export default defineEventHandler((event) => {
  requireStaff(event)
  return stats(useCtx())
})
