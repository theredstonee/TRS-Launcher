import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { requireAdmin } from '../../../../../lib/http'
import { listFilter } from '../../../../../lib/safety'

export default defineEventHandler((event) => {
  requireAdmin(event)
  return { words: listFilter(useCtx()) }
})
