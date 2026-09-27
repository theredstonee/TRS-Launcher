import { defineEventHandler } from 'h3'
import { adminJobs } from '../../../../lib/applications'
import { useCtx } from '../../../../lib/context'
import { requireStaff } from '../../../../lib/http'

/** Alle Stellen inkl. Entwürfe, mit Zahl der (offenen) Bewerbungen. */
export default defineEventHandler((event) => {
  requireStaff(event, ['applications.view', 'applications.manage'])
  return { jobs: adminJobs(useCtx()) }
})
