import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { myHelping } from '../../../../../lib/creators'
import { requireUser } from '../../../../../lib/http'

/** Offene Helfer-Einladungen an mich und wo ich helfe (§35.9). → `{ helping, helperInvites }` */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  return myHelping(useCtx(), auth.uuid)
})
