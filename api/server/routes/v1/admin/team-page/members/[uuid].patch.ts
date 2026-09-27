import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { uuidSchema } from '../../../../../lib/schemas'
import {
  MAX_TEAM_PAGE_LINKS,
  discordNameSchema,
  roleIdSchema,
  teamPageAdmin,
  teamPageLinkSchema,
  teamPageTitlesSchema,
  updateTeamPageMember,
} from '../../../../../lib/teampage'

const Body = z.strictObject({
  roleId: roleIdSchema.optional(),
  titles: teamPageTitlesSchema.optional(),
  discord: discordNameSchema.nullable().optional(),
  links: z.array(teamPageLinkSchema).max(MAX_TEAM_PAGE_LINKS).optional(),
})

/** Eintrag auf der Team-Seite ändern (Gruppe, Titel, Discord, Links). → neue Ansicht. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'team.page')
  limit(`admin-teampage:${staff.uuid}`, RULES.adminTeamPage)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  const body = await readJson(event, Body)
  const ctx = useCtx()
  updateTeamPageMember(ctx, staff, uuid, body)
  return teamPageAdmin(ctx, staff)
})
