import { defineEventHandler } from 'h3'
import { audit } from '../../../../../../lib/audit'
import { useCtx } from '../../../../../../lib/context'
import { adminCraftingBlock, blockBody, partnerServerId } from '../../../../../../lib/craftinghosting'
import { noContent, paramWith, readJson, requireStaff } from '../../../../../../lib/http'

/** Server sperren (stoppt ihn, Starten/Aufwecken verboten). */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'worlds.close')
  const id = paramWith(event, 'id', partnerServerId)
  const body = await readJson(event, blockBody)
  const ctx = useCtx()
  const owner = await adminCraftingBlock(ctx, id, staff.uuid, body.reason)
  audit(ctx, staff.uuid, 'craftinghosting.block', owner, body.reason)
  return noContent(event)
})
