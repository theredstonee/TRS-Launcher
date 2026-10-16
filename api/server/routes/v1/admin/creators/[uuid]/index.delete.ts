import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { adminDeleteCreator } from '../../../../../lib/creators'
import { limit, noContent, paramWith, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { uuidSchema } from '../../../../../lib/schemas'

/** Creator endgültig löschen (§35.6) – Codes, Symbole, Fan-Umhänge, Statistik, Helfer. Das Konto bleibt. → 204 */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'creators.manage')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  adminDeleteCreator(useCtx(), staff, paramWith(event, 'uuid', uuidSchema))
  return noContent(event)
})
