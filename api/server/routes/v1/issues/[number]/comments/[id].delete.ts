import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { noContent } from '../../../../../lib/http'
import { commentIdParam, issueNumberParam, requireIssueWriter } from '../../../../../lib/issue-http'
import { deleteOwnComment } from '../../../../../lib/issues'

/** Eigenen Kommentar löschen (Platzhalter bleibt, Bilder gehen). */
export default defineEventHandler((event) => {
  const viewer = requireIssueWriter(event, 'issueEditUser')
  deleteOwnComment(useCtx(), viewer, issueNumberParam(event), commentIdParam(event))
  return noContent(event)
})
