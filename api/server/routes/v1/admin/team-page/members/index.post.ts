import { defineEventHandler, setResponseStatus } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { notFound } from '../../../../../lib/errors'
import { limit, readJson, requireStaff } from '../../../../../lib/http'
import { normalizeUuid } from '../../../../../lib/ids'
import { RULES } from '../../../../../lib/ratelimit'
import { addTeamPageMember, roleIdSchema, teamPageAdmin } from '../../../../../lib/teampage'
import { getUserByName } from '../../../../../lib/users'

const Body = z.strictObject({
  /** UUID oder (zuletzt gesehener) Minecraft-Name. */
  player: z.string().trim().min(1).max(36),
  roleId: roleIdSchema.optional(),
})

/** Person zur Team-Seite hinzufügen (§26.2). → 201 + neue Ansicht. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'team.page')
  limit(`admin-teampage:${staff.uuid}`, RULES.adminTeamPage)
  const body = await readJson(event, Body)
  const ctx = useCtx()
  let uuid = normalizeUuid(body.player)
  if (!uuid && /^[A-Za-z0-9_]{1,16}$/.test(body.player)) uuid = getUserByName(ctx, body.player)?.uuid ?? null
  if (!uuid) throw notFound('user_not_found', 'This player has never signed in to TRS')
  addTeamPageMember(ctx, staff, uuid, body.roleId)
  setResponseStatus(event, 201)
  return teamPageAdmin(ctx, staff)
})
