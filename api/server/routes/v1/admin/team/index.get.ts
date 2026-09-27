import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { requireStaff } from '../../../../lib/http'
import { PERMISSION_GROUPS, listMembers, listRoleViews, myTeamView } from '../../../../lib/team'

/** Rollen-Verwaltung (§24.2): alle Rollen, Mitglieder, Rechte-Katalog und die eigenen Grenzen. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'roles.manage')
  const ctx = useCtx()
  return {
    roles: listRoleViews(ctx, staff),
    members: listMembers(ctx, staff),
    permissionGroups: PERMISSION_GROUPS,
    me: myTeamView(ctx, staff),
  }
})
