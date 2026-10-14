import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { requireStaff } from '../../../../../lib/http'
import { issueNumberParam } from '../../../../../lib/issue-http'
import { claimIssue } from '../../../../../lib/issues'

/** Hand auf dem Board (§28.4): sich selbst als zuständig eintragen, solange niemand sonst es ist. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, ['issues.manage'])
  return claimIssue(useCtx(), staff, issueNumberParam(event), true)
})
