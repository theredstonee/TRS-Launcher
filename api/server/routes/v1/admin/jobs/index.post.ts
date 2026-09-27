import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { createJob } from '../../../../lib/applications'
import { useCtx } from '../../../../lib/context'
import { created, readJson, requireStaff } from '../../../../lib/http'

/** Stelle anlegen (Texte je Sprache + Formular). 201 `{ job }`. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'applications.manage')
  const body = await readJson(event, z.unknown(), 128 * 1024)
  return created(event, { job: createJob(useCtx(), staff, body) })
})
