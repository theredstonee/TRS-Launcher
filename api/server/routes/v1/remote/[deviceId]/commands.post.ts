import { defineEventHandler, setResponseStatus } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { limit, paramWith, readJson } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { COMMAND_TYPES, IDEMPOTENCY_KEY, sendCommand } from '../../../../lib/remote'
import { requireDevice } from '../../../../lib/remote-http'

const body = z.strictObject({
  type: z.enum(COMMAND_TYPES),
  args: z.record(z.string().max(32), z.string().max(64)).optional(),
  idempotencyKey: z.string().regex(IDEMPOTENCY_KEY),
})

/**
 * Handy: Befehl an einen gekoppelten PC (§33.5). Geht als `remote_command` (signiert, 60 s gültig) an den PC; der
 * führt ihn nur aus, wenn er die Art erlaubt. Gleicher `idempotencyKey` → derselbe Befehl (`200`, `duplicate: true`).
 */
export default defineEventHandler(async (event) => {
  const { device } = requireDevice(event, 'write', 'phone')
  const desktopId = paramWith(event, 'deviceId', z.string().max(64))
  const input = await readJson(event, body)
  limit(`remoteCmd:${device.id}`, RULES.remoteCommandPhone)
  limit(`remoteCmdBurst:${device.id}`, RULES.remoteCommandBurst)
  limit(`remoteCmdPc:${desktopId}`, RULES.remoteCommandDesktop)
  const result = sendCommand(useCtx(), device, desktopId, { type: input.type, args: input.args ?? {}, idempotencyKey: input.idempotencyKey })
  setResponseStatus(event, result.duplicate ? 200 : 202)
  return result
})
