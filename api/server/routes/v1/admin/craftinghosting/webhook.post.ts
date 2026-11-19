import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { audit } from '../../../../lib/audit'
import { useCtx } from '../../../../lib/context'
import { adminWebhook } from '../../../../lib/craftinghosting'
import { readJson, requireStaff } from '../../../../lib/http'

const body = z.object({ action: z.enum(['setup', 'test', 'remove']) }).strict()

/** Webhook beim Partner einrichten/testen/entfernen. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'worlds.close')
  const b = await readJson(event, body)
  const ctx = useCtx()
  const out = await adminWebhook(ctx, b.action, staff.uuid)
  if (b.action !== 'test') audit(ctx, staff.uuid, `craftinghosting.webhook.${b.action}`, null, out.url ?? '')
  return out
})
