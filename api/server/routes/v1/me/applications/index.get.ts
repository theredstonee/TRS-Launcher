import { defineEventHandler } from 'h3'
import { myApplications } from '../../../../lib/applications'
import { useCtx } from '../../../../lib/context'
import { requireWebOrUser } from '../../../../lib/http'

/** Eigene Bewerbungen mit Status und Antwort des Teams (Website oder Launcher). */
export default defineEventHandler((event) => {
  const me = requireWebOrUser(event)
  return { applications: myApplications(useCtx(), me.uuid) }
})
