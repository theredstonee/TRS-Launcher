import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { readJson, requireStaff } from '../../../../lib/http'
import { adminIssuePatchBody, issueNumberParam } from '../../../../lib/issue-http'
import { adminUpdateIssue } from '../../../../lib/issues'

/** Triage (§28.4): Status, Priorität, Zuständig, Tags, „Erledigt in“, Art, Bereich, Titel, Kommentare schließen. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, ['issues.manage', 'issues.moderate'])
  const number = issueNumberParam(event)
  const body = await readJson(event, adminIssuePatchBody)
  return adminUpdateIssue(useCtx(), staff, number, body)
})
