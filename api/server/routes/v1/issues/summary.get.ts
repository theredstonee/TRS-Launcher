import { defineEventHandler, setResponseHeader } from 'h3'
import { useCtx } from '../../../lib/context'
import { publicIssueRead } from '../../../lib/issue-http'
import { issueSummary } from '../../../lib/issues'

/** Zähler für die Seitenleiste „Workspace“ (§28.3): offen je Status; angemeldet eigene/gefolgte, Team: neue. */
export default defineEventHandler((event) => {
  const viewer = publicIssueRead(event)
  if (viewer) setResponseHeader(event, 'Cache-Control', 'private, no-store')
  return issueSummary(useCtx(), viewer)
})
