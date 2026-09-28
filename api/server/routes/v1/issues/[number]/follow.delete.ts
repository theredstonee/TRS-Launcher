import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { noContent } from '../../../../lib/http'
import { issueNumberParam, requireIssueWriter } from '../../../../lib/issue-http'
import { setFollow } from '../../../../lib/issues'

/** Nicht mehr folgen. */
export default defineEventHandler((event) => {
  const viewer = requireIssueWriter(event, 'issueFollowUser')
  setFollow(useCtx(), viewer, issueNumberParam(event), false)
  return noContent(event)
})
