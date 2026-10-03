import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { limit, noContent, paramWith, readJson } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { ERROR_CODE, finishCommand } from '../../../../../lib/remote'
import { requireDevice } from '../../../../../lib/remote-http'

const body = z.strictObject({ ok: z.boolean(), error: z.string().regex(ERROR_CODE).nullable().optional() })

/** PC: Ergebnis eines abgeholten Befehls (§33.5). Das Handy bekommt `remote_command_update`. */
export default defineEventHandler(async (event) => {
  const { device } = requireDevice(event, 'read', 'desktop')
  limit(`remoteClaim:${device.id}`, RULES.remoteClaimDesktop)
  const id = paramWith(event, 'id', z.string().max(64))
  const input = await readJson(event, body)
  finishCommand(useCtx(), device, id, input.ok, input.error ?? null)
  return noContent(event)
})
