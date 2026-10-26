import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { limit, requireStaff } from '../../../../../lib/http'
import { assertKeyAdmin, listKeys } from '../../../../../lib/mail/keys'
import { RULES } from '../../../../../lib/ratelimit'

/** Newsletter-API-Schlüssel (§40.5) – nur Owner/Admin. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event)
  assertKeyAdmin(staff)
  limit(`adminNl:${staff.uuid}`, RULES.adminNewsletter)
  return { keys: listKeys(useCtx()) }
})
