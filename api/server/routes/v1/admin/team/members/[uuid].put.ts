import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { plainText, uuidSchema } from '../../../../../lib/schemas'
import { listMembers, setMemberRoles } from '../../../../../lib/team'

const Body = z.strictObject({
  roles: z.array(z.string().regex(/^[a-z][a-z0-9_]{1,39}$/)).max(10),
  note: plainText(200).nullable().optional(),
})

/** Rollen eines Mitglieds setzen (vollständige Liste; leer = aus dem Team). → `{ member, members }`. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'roles.manage')
  limit(`admin-roles:${staff.uuid}`, RULES.adminSanction)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  const body = await readJson(event, Body)
  const ctx = useCtx()
  const member = setMemberRoles(ctx, staff, uuid, body.roles, body.note)
  return { member, members: listMembers(ctx, staff) }
})
