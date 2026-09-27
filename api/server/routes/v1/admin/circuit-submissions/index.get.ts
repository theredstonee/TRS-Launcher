import { defineEventHandler } from 'h3'
import { listAdminSubmissions, pendingSubmissionCount, submissionListQuery } from '../../../../lib/circuits'
import { useCtx } from '../../../../lib/context'
import { queryWith, requireStaff } from '../../../../lib/http'

/** Team (§25.5): Einreichungen (Standard: offene, älteste zuerst). */
export default defineEventHandler((event) => {
  requireStaff(event, 'circuits.manage')
  const q = queryWith(event, submissionListQuery)
  const ctx = useCtx()
  return { submissions: listAdminSubmissions(ctx, q.status), pending: pendingSubmissionCount(ctx) }
})
