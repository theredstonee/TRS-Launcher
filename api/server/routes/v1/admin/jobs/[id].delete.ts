import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { JOB_ID, deleteJob } from '../../../../lib/applications'
import { useCtx } from '../../../../lib/context'
import { noContent, paramWith, requireStaff } from '../../../../lib/http'

/** Stelle ohne Bewerbungen löschen (sonst 409 job_has_applications → schließen). */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'applications.manage')
  deleteJob(useCtx(), staff, paramWith(event, 'id', z.string().regex(JOB_ID)))
  return noContent(event)
})
