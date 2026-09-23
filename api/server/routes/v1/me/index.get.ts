import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { requireUser } from '../../../lib/http'
import { meView } from '../../../lib/users'

export default defineEventHandler((event) => {
  const auth = requireUser(event)
  return meView(useCtx(), auth.user)
})
