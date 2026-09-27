import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { JOB_ID, updateJob } from '../../../../lib/applications'
import { useCtx } from '../../../../lib/context'
import { paramWith, readJson, requireStaff } from '../../../../lib/http'

/** Stelle ändern (vollständig). Laufende Bewerbungen behalten ihr Formular. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'applications.manage')
  const id = paramWith(event, 'id', z.string().regex(JOB_ID))
  const body = await readJson(event, z.unknown(), 128 * 1024)
  return { job: updateJob(useCtx(), staff, id, body) }
})
