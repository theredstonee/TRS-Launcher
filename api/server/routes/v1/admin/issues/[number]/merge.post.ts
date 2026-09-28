import { defineEventHandler } from 'h3'
import { touchIssueAuthor } from '../../../../../lib/achievements'
import { useCtx } from '../../../../../lib/context'
import { readJson, requireStaff } from '../../../../../lib/http'
import { issueNumberParam, mergeBody } from '../../../../../lib/issue-http'
import { mergeIssue } from '../../../../../lib/issues'

/** Duplikat in ein anderes Issue zusammenführen (Stimmen + Folgende wandern mit). */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'issues.manage')
  const number = issueNumberParam(event)
  const body = await readJson(event, mergeBody)
  const ctx = useCtx()
  const issue = mergeIssue(ctx, staff, number, body.into)
  // Erfolge (§31): Stimmen wandern zum Ziel → dessen Ersteller prüfen.
  touchIssueAuthor(ctx, body.into)
  return { issue }
})
