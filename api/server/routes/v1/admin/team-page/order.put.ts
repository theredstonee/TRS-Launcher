import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { limit, readJson, requireStaff } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { uuidSchema } from '../../../../lib/schemas'
import { MAX_TEAM_PAGE_MEMBERS, reorderTeamPage, roleIdSchema, teamPageAdmin } from '../../../../lib/teampage'

const Body = z.strictObject({
  groups: z.array(z.strictObject({
    roleId: roleIdSchema,
    uuids: z.array(uuidSchema).max(MAX_TEAM_PAGE_MEMBERS),
  })).min(1).max(100),
})

/** Neue Reihenfolge nach Drag & Drop (je Gruppe vollständig). → neue Ansicht. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'team.page')
  limit(`admin-teampage:${staff.uuid}`, RULES.adminTeamPage)
  const body = await readJson(event, Body)
  const ctx = useCtx()
  reorderTeamPage(ctx, staff, body.groups)
  return teamPageAdmin(ctx, staff)
})
