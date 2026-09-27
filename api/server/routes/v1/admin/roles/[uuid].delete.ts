import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { paramWith, requireStaff } from '../../../../lib/http'
import { uuidSchema } from '../../../../lib/schemas'
import { removeRole } from '../../../../lib/staff'

export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'roles.manage')
  return { roles: removeRole(useCtx(), staff, paramWith(event, 'uuid', uuidSchema)) }
})
