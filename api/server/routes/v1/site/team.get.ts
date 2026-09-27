import { defineEventHandler, setResponseHeader } from 'h3'
import { publicJobs } from '../../../lib/applications'
import { useCtx } from '../../../lib/context'
import { publicTeam } from '../../../lib/team'

/** Website: Team-Seite (§23.3) – öffentliche Rollen mit Mitgliedern und offene Stellen. 60 s cachebar. */
export default defineEventHandler((event) => {
  const ctx = useCtx()
  setResponseHeader(event, 'Cache-Control', 'public, max-age=60')
  return { team: publicTeam(ctx), jobs: publicJobs(ctx) }
})
