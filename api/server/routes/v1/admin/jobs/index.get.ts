import { defineEventHandler } from 'h3'
import { adminJobs } from '../../../../lib/applications'
import { useCtx } from '../../../../lib/context'
import { all } from '../../../../lib/db'
import { requireStaff } from '../../../../lib/http'

/** Alle Stellen inkl. Entwürfe, mit Zahl der (offenen) Bewerbungen, dazu die verknüpfbaren Rollen (ohne Owner). */
export default defineEventHandler((event) => {
  requireStaff(event, ['applications.view', 'applications.manage'])
  const ctx = useCtx()
  const roles = all<{ id: string, name: string | null, color: string, builtin: number, rank: number }>(
    ctx.db, "SELECT id, name, color, builtin, rank FROM team_roles WHERE id <> 'owner' ORDER BY rank DESC",
  ).map((r) => ({ id: r.id, name: r.name, color: r.color, builtin: r.builtin === 1, rank: r.rank }))
  return { jobs: adminJobs(ctx), roles }
})
