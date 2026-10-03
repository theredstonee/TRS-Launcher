import { defineEventHandler, setResponseHeader } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { claimCommand } from '../../../../../lib/remote'
import { requireDevice } from '../../../../../lib/remote-http'

/**
 * PC: Befehl vor dem Ausführen abholen (§33.5) – genau einmal (`409 remote_command_claimed`) und nur vor dem Ablauf
 * (`410 remote_command_expired`). Liefert Art und geprüfte Argumente.
 */
export default defineEventHandler((event) => {
  const { device } = requireDevice(event, 'read', 'desktop')
  limit(`remoteClaim:${device.id}`, RULES.remoteClaimDesktop)
  const id = paramWith(event, 'id', z.string().max(64))
  setResponseHeader(event, 'Cache-Control', 'no-store')
  return { command: claimCommand(useCtx(), device, id) }
})
