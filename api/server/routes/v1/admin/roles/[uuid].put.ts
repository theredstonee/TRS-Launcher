import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { paramWith, readJson, requireStaff } from '../../../../lib/http'
import { roleBody, uuidSchema } from '../../../../lib/schemas'
import { setRole } from '../../../../lib/staff'

/** Rolle vergeben/ändern (nur Admins; ADMIN_UUIDS und die eigene Rolle sind fest). */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'admin')
  const uuid = paramWith(event, 'uuid', uuidSchema)
  const body = await readJson(event, roleBody)
  return { roles: setRole(useCtx(), staff, uuid, body.role, body.note) }
})
