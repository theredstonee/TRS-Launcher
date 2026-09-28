import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { readJson, requireStaff } from '../../../../../lib/http'
import { issueNumberParam, mergeBody } from '../../../../../lib/issue-http'
import { mergeIssue } from '../../../../../lib/issues'

/** Duplikat in ein anderes Issue zusammenführen (Stimmen + Folgende wandern mit). */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'issues.manage')
  const number = issueNumberParam(event)
  const body = await readJson(event, mergeBody)
  return { issue: mergeIssue(useCtx(), staff, number, body.into) }
})
