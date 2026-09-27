import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { paramWith, requireStaff } from '../../../../../lib/http'
import { uuidSchema } from '../../../../../lib/schemas'
import { listMembers, setMemberRoles } from '../../../../../lib/team'

/** Aus dem Team entfernen (alle Rollen). → `{ members }`. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'roles.manage')
  const ctx = useCtx()
  setMemberRoles(ctx, staff, paramWith(event, 'uuid', uuidSchema), [])
  return { members: listMembers(ctx, staff) }
})
