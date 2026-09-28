import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { queryWith, requireStaff } from '../../../../lib/http'
import { adminIssueListQuery } from '../../../../lib/issue-http'
import { listIssues } from '../../../../lib/issues'
import { getUser } from '../../../../lib/users'

/** Team-Liste (§28.4): wie öffentlich, dazu Ansichten (nicht zugewiesen, meine, gelöscht) und Priorität. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, ['issues.manage', 'issues.moderate'])
  const q = queryWith(event, adminIssueListQuery)
  const ctx = useCtx()
  return listIssues(ctx, q, { uuid: staff.uuid, name: getUser(ctx, staff.uuid)?.name ?? '', staff })
})
