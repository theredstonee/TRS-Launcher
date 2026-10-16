import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { adminRestoreCreator } from '../../../../../lib/creators'
import { limit, paramWith, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { uuidSchema } from '../../../../../lib/schemas'

/** Entzogenen/gesperrten Creator wieder aktiv machen (§35.6). → `{ creator }` */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'creators.manage')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  return adminRestoreCreator(useCtx(), staff, paramWith(event, 'uuid', uuidSchema))
})
