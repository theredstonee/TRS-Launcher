import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { search } from '../../../lib/dashboard'
import { limit, queryWith, requireStaff } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { searchQuery } from '../../../lib/schemas'

/** Globale Suche (§22.7): Spielername/UUID, Melde-ID, Strafe (#id), Umhang/Kosmetik. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event)
  limit(`admin-search:${staff.uuid}`, RULES.adminSearch)
  return search(useCtx(), queryWith(event, searchQuery).q)
})
