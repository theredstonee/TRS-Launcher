import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { created, readJson } from '../../../../../lib/http'
import { commentBody, issueNumberParam, requireIssueWriter } from '../../../../../lib/issue-http'
import { addComment } from '../../../../../lib/issues'

/** Kommentar schreiben (Markdown, optional Bilder). Kommentieren = folgen; Team-Antworten benachrichtigen Folgende. */
export default defineEventHandler(async (event) => {
  const viewer = requireIssueWriter(event, 'issueCommentUser')
  const number = issueNumberParam(event)
  const body = await readJson(event, commentBody, 32 * 1024)
  return created(event, { comment: addComment(useCtx(), viewer, number, body) })
})
