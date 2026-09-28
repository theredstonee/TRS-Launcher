import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { requireStaff } from '../../../../../lib/http'
import { issueNumberParam } from '../../../../../lib/issue-http'
import { adminRestoreIssue } from '../../../../../lib/issues'

/** Gelöschtes Issue wiederherstellen. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'issues.moderate')
  return { issue: adminRestoreIssue(useCtx(), staff, issueNumberParam(event)) }
})
