import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { audit } from '../../../../../../lib/audit'
import { useCtx } from '../../../../../../lib/context'
import { adminCraftingDelete, partnerServerId } from '../../../../../../lib/craftinghosting'
import { noContent, paramWith, readJson, requireStaff } from '../../../../../../lib/http'

const body = z.object({ reason: z.string().trim().max(200).optional() }).strict().optional()

/** Server samt Daten löschen (Team). Für dauerhaftes Fernhalten: Strafe `hosting_ban`. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'worlds.close')
  const id = paramWith(event, 'id', partnerServerId)
  const b = await readJson(event, body)
  const ctx = useCtx()
  const owner = await adminCraftingDelete(ctx, id)
  audit(ctx, staff.uuid, 'craftinghosting.delete', owner, b?.reason || id)
  return noContent(event)
})
