import { defineEventHandler } from 'h3'
import { audit } from '../../../../../../lib/audit'
import { useCtx } from '../../../../../../lib/context'
import { adminCraftingResetQuota, partnerServerId } from '../../../../../../lib/craftinghosting'
import { noContent, paramWith, requireStaff } from '../../../../../../lib/http'

/** Laufzeit-Kontingent zurücksetzen und Pause aufheben (§43.17). */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'worlds.close')
  const id = paramWith(event, 'id', partnerServerId)
  const ctx = useCtx()
  const owner = adminCraftingResetQuota(ctx, id)
  audit(ctx, staff.uuid, 'craftinghosting.reset_quota', owner, id)
  return noContent(event)
})
