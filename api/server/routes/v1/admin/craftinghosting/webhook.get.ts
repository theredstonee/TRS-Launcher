import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { adminWebhook } from '../../../../lib/craftinghosting'
import { requireStaff } from '../../../../lib/http'

export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'worlds.view')
  return adminWebhook(useCtx(), 'status', staff.uuid)
})
