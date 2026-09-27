import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { search } from '../../../lib/dashboard'
import { limit, queryWith, requireStaff } from '../../../lib/http'
import { redactSummary } from '../../../lib/moderation'
import { RULES } from '../../../lib/ratelimit'
import { searchQuery } from '../../../lib/schemas'
import { can } from '../../../lib/team'

/** Globale Suche (§22.7): Spielername/UUID, Melde-ID, Strafe (#id), Umhang/Kosmetik – nur Gruppen, die das Recht erlaubt (§23.2). */
export default defineEventHandler((event) => {
  const staff = requireStaff(event)
  limit(`admin-search:${staff.uuid}`, RULES.adminSearch)
  const r = search(useCtx(), queryWith(event, searchQuery).q)
  const players = can(staff, 'players.view')
  const uploads = can(staff, 'uploads.review')
  return {
    players: players ? r.players : [],
    reports: can(staff, 'reports.view') ? (can(staff, 'reports.content') ? r.reports : r.reports.map(redactSummary)) : [],
    sanctions: players || can(staff, 'appeals.handle') ? r.sanctions : [],
    capes: uploads ? r.capes : [],
    cosmetics: uploads ? r.cosmetics : [],
  }
})
