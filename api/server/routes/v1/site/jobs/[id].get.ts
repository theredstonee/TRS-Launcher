import { defineEventHandler, setResponseHeader } from 'h3'
import { z } from 'zod'
import { JOB_ID, publicJob } from '../../../../lib/applications'
import { useCtx } from '../../../../lib/context'
import { paramWith } from '../../../../lib/http'

/** Website: eine Stelle mit Formular (offen oder geschlossen; Entwürfe → 404). */
export default defineEventHandler((event) => {
  const id = paramWith(event, 'id', z.string().regex(JOB_ID))
  setResponseHeader(event, 'Cache-Control', 'public, max-age=60')
  return { job: publicJob(useCtx(), id) }
})
