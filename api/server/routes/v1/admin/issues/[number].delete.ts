import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { noContent, readJson, requireStaff } from '../../../../lib/http'
import { deleteIssueBody, issueNumberParam } from '../../../../lib/issue-http'
import { adminDeleteIssue } from '../../../../lib/issues'

/** Issue löschen (versteckt, 90 Tage wiederherstellbar). */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'issues.moderate')
  const number = issueNumberParam(event)
  const body = await readJson(event, deleteIssueBody)
  adminDeleteIssue(useCtx(), staff, number, body?.reason?.trim() || null)
  return noContent(event)
})
