import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { listFriends } from '../../../lib/friends'
import { requireUser } from '../../../lib/http'

export default defineEventHandler((event) => {
  const auth = requireUser(event)
  return listFriends(useCtx(), auth.uuid)
})
