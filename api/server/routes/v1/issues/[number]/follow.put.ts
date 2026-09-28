import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { noContent } from '../../../../lib/http'
import { issueNumberParam, requireIssueWriter } from '../../../../lib/issue-http'
import { setFollow } from '../../../../lib/issues'

/** Issue folgen (Benachrichtigungen bei Status, Team-Antwort, „Erledigt in“). */
export default defineEventHandler((event) => {
  const viewer = requireIssueWriter(event, 'issueFollowUser')
  setFollow(useCtx(), viewer, issueNumberParam(event), true)
  return noContent(event)
})
