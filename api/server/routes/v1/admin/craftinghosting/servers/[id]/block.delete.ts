import { defineEventHandler } from 'h3'
import { audit } from '../../../../../../lib/audit'
import { useCtx } from '../../../../../../lib/context'
import { adminCraftingBlock, partnerServerId } from '../../../../../../lib/craftinghosting'
import { noContent, paramWith, requireStaff } from '../../../../../../lib/http'

export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'worlds.close')
  const id = paramWith(event, 'id', partnerServerId)
  const ctx = useCtx()
  const owner = await adminCraftingBlock(ctx, id, staff.uuid, null)
  audit(ctx, staff.uuid, 'craftinghosting.unblock', owner, id)
  return noContent(event)
})
