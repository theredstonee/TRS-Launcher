import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { noContent, paramWith, requireStaff } from '../../../../../lib/http'
import { deleteRole } from '../../../../../lib/team'

/** Eigene Rolle löschen (feste Rollen nie). Mitglieder verlieren sie, verknüpfte Stellen verlieren die Rolle. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'roles.manage')
  deleteRole(useCtx(), staff, paramWith(event, 'id', z.string().regex(/^[a-z][a-z0-9_]{1,39}$/)))
  return noContent(event)
})
