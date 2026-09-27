import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { APPLICATION_STATUSES, JOB_ID, adminListApplications } from '../../../../lib/applications'
import { useCtx } from '../../../../lib/context'
import { queryWith, requireStaff } from '../../../../lib/http'

const Query = z.strictObject({
  status: z.enum([...APPLICATION_STATUSES, 'open', 'all']).default('open'),
  job: z.string().regex(JOB_ID).optional(),
  q: z.string().regex(/^[A-Za-z0-9_]{1,16}$/).optional(),
  cursor: z.string().max(200).optional(),
  limit: z.coerce.number().int().min(1).max(100).default(50),
})

/** Bewerbungen mit Filtern (Status, Stelle, Name) – eigene Bewerbungen nie. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'applications.view')
  return adminListApplications(useCtx(), staff, queryWith(event, Query))
})
