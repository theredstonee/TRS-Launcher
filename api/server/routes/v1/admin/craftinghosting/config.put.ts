import { defineEventHandler } from 'h3'
import { audit } from '../../../../lib/audit'
import { useCtx } from '../../../../lib/context'
import { adminConfigBody, adminSetCraftingConfig } from '../../../../lib/craftinghosting'
import { readJson, requireStaff } from '../../../../lib/http'

export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'worlds.close')
  const body = await readJson(event, adminConfigBody, 32 * 1024)
  const ctx = useCtx()
  const out = adminSetCraftingConfig(ctx, staff.uuid, body)
  const what = [body.notice !== undefined && 'notice', body.maintenance && `maintenance=${body.maintenance.active}`, body.features && `features=${Object.keys(body.features).join(',')}`].filter(Boolean).join(' ')
  audit(ctx, staff.uuid, 'craftinghosting.config', null, what.slice(0, 200))
  return out
})
