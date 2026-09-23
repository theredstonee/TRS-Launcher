import { defineEventHandler } from 'h3'
import { listCodes } from '../../../../lib/codes'
import { useCtx } from '../../../../lib/context'
import { requireAdmin } from '../../../../lib/http'

/** Codes ohne Klartext (nur die letzten 4 Zeichen als `hint`). */
export default defineEventHandler((event) => {
  requireAdmin(event)
  return { codes: listCodes(useCtx()) }
})
