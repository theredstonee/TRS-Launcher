import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { limit, paramWith, readJson, requireUser } from '../../../../lib/http'
import { sendPack } from '../../../../lib/packs'
import { RULES } from '../../../../lib/ratelimit'
import { uuidSchema } from '../../../../lib/schemas'

/** Pack an Freunde schicken (bis 20 je Aufruf). Nicht-Freunde und Blockierte werden übersprungen (`skipped`). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`packManage:${auth.uuid}`, RULES.packManageUser)
  const id = paramWith(event, 'id', z.string().max(64))
  const body = await readJson(event, z.strictObject({ to: z.array(uuidSchema).min(1).max(20) }))
  return sendPack(useCtx(), auth.uuid, id, body.to)
})
