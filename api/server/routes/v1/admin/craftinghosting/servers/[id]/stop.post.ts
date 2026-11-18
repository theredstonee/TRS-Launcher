import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { audit } from '../../../../../../lib/audit'
import { useCtx } from '../../../../../../lib/context'
import { adminCraftingStop, partnerServerId } from '../../../../../../lib/craftinghosting'
import { noContent, paramWith, readJson, requireStaff } from '../../../../../../lib/http'

const body = z.object({ reason: z.string().trim().max(200).optional() }).strict().optional()

export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'worlds.close')
  const id = paramWith(event, 'id', partnerServerId)
  const b = await readJson(event, body)
  const ctx = useCtx()
  const owner = await adminCraftingStop(ctx, id)
  audit(ctx, staff.uuid, 'craftinghosting.stop', owner, b?.reason || id)
  return noContent(event)
})
