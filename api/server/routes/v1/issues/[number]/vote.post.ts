import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { readJson } from '../../../../lib/http'
import { issueNumberParam, requireIssueWriter, voteBody } from '../../../../lib/issue-http'
import { voteIssue } from '../../../../lib/issues'

/** Hoch (1), runter (-1) oder Stimme zurücknehmen (0) – je Konto eine Stimme. */
export default defineEventHandler(async (event) => {
  const viewer = requireIssueWriter(event, 'issueVoteUser')
  const number = issueNumberParam(event)
  const body = await readJson(event, voteBody)
  return voteIssue(useCtx(), viewer, number, body.vote)
})
