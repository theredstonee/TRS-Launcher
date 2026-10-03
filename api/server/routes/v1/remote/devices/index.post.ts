import { defineEventHandler, setResponseHeader } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { created, limit, readJson, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { registerDevice } from '../../../../lib/remote'

const body = z.strictObject({ kind: z.enum(['desktop', 'phone']), name: z.string().max(200) })

/**
 * Gerät für die Fernbedienung anmelden (§34.1). Das Geheimnis gibt es nur in dieser Antwort – der Client speichert es
 * verschlüsselt und schickt es danach als `X-TRS-Device: <id>.<geheimnis>` mit.
 */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`remoteRegister:${auth.uuid}`, RULES.remoteRegisterUser)
  const input = await readJson(event, body)
  setResponseHeader(event, 'Cache-Control', 'no-store')
  return created(event, registerDevice(useCtx(), auth.uuid, input.kind, input.name))
})
