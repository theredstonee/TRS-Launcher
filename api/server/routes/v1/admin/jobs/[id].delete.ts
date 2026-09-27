import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { JOB_ID, deleteJob } from '../../../../lib/applications'
import { useCtx } from '../../../../lib/context'
import { limit, noContent, paramWith, queryWith, requireStaff } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

const Query = z.object({ withApplications: z.enum(['1', 'true']).optional() })

/** Stelle löschen – nur Owner. Mit Bewerbungen nur mit `?withApplications=1` (sonst 409 job_has_applications). */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'applications.view')
  limit(`admin-jobs-delete:${staff.uuid}`, RULES.adminSanction)
  const q = queryWith(event, Query)
  deleteJob(useCtx(), staff, paramWith(event, 'id', z.string().regex(JOB_ID)), q.withApplications !== undefined)
  return noContent(event)
})
