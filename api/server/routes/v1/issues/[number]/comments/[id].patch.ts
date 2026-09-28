import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { readJson } from '../../../../../lib/http'
import { commentIdParam, editCommentBody, issueNumberParam, requireIssueWriter } from '../../../../../lib/issue-http'
import { editComment } from '../../../../../lib/issues'

/** Eigenen Kommentar bearbeiten. */
export default defineEventHandler(async (event) => {
  const viewer = requireIssueWriter(event, 'issueEditUser')
  const number = issueNumberParam(event)
  const id = commentIdParam(event)
  const body = await readJson(event, editCommentBody, 32 * 1024)
  return { comment: editComment(useCtx(), viewer, number, id, body.body) }
})
