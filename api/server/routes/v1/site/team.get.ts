import { defineEventHandler, setResponseHeader } from 'h3'
import { publicJobs } from '../../../lib/applications'
import { useCtx } from '../../../lib/context'
import { publicTeamPage } from '../../../lib/teampage'

/**
 * Website: Team-Seite (§26.1) – die im Admin eingetragenen Mitglieder je öffentlicher Rolle (Skin, Umhang, Titel,
 * Discord, Links) und die offenen Stellen. 60 s cachebar.
 */
export default defineEventHandler(async (event) => {
  const ctx = useCtx()
  const team = await publicTeamPage(ctx)
  setResponseHeader(event, 'Cache-Control', 'public, max-age=60')
  return { team, jobs: publicJobs(ctx) }
})
