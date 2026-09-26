import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { playerFile } from '../../../../../lib/dossier'
import { paramWith, requireStaff } from '../../../../../lib/http'
import { uuidSchema } from '../../../../../lib/schemas'

/** Spieler-Akte (§22.4). */
export default defineEventHandler((event) => {
  const staff = requireStaff(event)
  return { file: playerFile(useCtx(), staff, paramWith(event, 'uuid', uuidSchema)) }
})
