import { defineEventHandler } from 'h3'
import { useCtx } from '../../lib/context'
import { one } from '../../lib/db'
import { unavailable } from '../../lib/errors'

export default defineEventHandler(() => {
  const ctx = useCtx()
  try {
    one(ctx.db, 'SELECT 1 AS ok')
  } catch {
    throw unavailable('database_unavailable', 'Database is not available')
  }
  return { status: 'ok', version: 1, time: new Date(ctx.now()).toISOString() }
})
