import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { requireStaff } from '../../../../../lib/http'
import { issueNumberParam } from '../../../../../lib/issue-http'
import { claimIssue } from '../../../../../lib/issues'

/** Eigenen Claim wieder lösen (§28.4); fremde Claims bleiben. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, ['issues.manage'])
  return claimIssue(useCtx(), staff, issueNumberParam(event), false)
})
