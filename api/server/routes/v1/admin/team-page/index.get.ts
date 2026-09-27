import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { requireStaff } from '../../../../lib/http'
import { teamPageAdmin } from '../../../../lib/teampage'

/** Team-Seite im Admin (§26.2): alle Gruppen (Rollen nach Rang) mit den eingetragenen Mitgliedern. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'team.page')
  return teamPageAdmin(useCtx(), staff)
})
