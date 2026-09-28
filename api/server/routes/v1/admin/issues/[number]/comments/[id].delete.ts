import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../../lib/context'
import { noContent, requireStaff } from '../../../../../../lib/http'
import { commentIdParam, issueNumberParam } from '../../../../../../lib/issue-http'
import { adminDeleteComment } from '../../../../../../lib/issues'

/** Kommentar entfernen (Platzhalter „vom Team entfernt“). */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'issues.moderate')
  adminDeleteComment(useCtx(), staff, issueNumberParam(event), commentIdParam(event))
  return noContent(event)
})
