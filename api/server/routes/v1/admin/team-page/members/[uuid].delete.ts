import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { uuidSchema } from '../../../../../lib/schemas'
import { removeTeamPageMember, teamPageAdmin } from '../../../../../lib/teampage'

/** Person von der Team-Seite nehmen (Rollen bleiben unverändert). → neue Ansicht. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'team.page')
  limit(`admin-teampage:${staff.uuid}`, RULES.adminTeamPage)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  const ctx = useCtx()
  removeTeamPageMember(ctx, staff, uuid)
  return teamPageAdmin(ctx, staff)
})
