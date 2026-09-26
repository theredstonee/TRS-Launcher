import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { requireStaff } from '../../../../../lib/http'
import { listFilter } from '../../../../../lib/safety'

export default defineEventHandler((event) => {
  requireStaff(event)
  return { words: listFilter(useCtx()) }
})
