import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { requireStaff } from '../../../../lib/http'
import { listRoles } from '../../../../lib/staff'

/** Team: Admins (ADMIN_UUIDS + vergebene) und Moderatoren. Nur Admins. */
export default defineEventHandler((event) => {
  requireStaff(event, 'admin')
  return { roles: listRoles(useCtx()) }
})
