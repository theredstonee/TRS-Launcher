import { defineEventHandler, setResponseHeader } from 'h3'
import { useCtx } from '../../../lib/context'
import { issueNumberParam, publicIssueRead } from '../../../lib/issue-http'
import { issuePage } from '../../../lib/issues'

/** Ein Issue mit Kommentaren und Verlauf (§28.3). Log nur für Ersteller + Team, Notizen nur mit issues.manage. */
export default defineEventHandler((event) => {
  const viewer = publicIssueRead(event)
  const number = issueNumberParam(event)
  if (viewer) setResponseHeader(event, 'Cache-Control', 'private, no-store')
  return issuePage(useCtx(), number, viewer)
})
