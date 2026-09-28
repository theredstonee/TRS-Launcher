import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { queryWith } from '../../../lib/http'
import { issueListQuery, publicIssueRead } from '../../../lib/issue-http'
import { listIssues } from '../../../lib/issues'

/** Öffentliche Issue-Liste (§28.3): Sortierung, Filter, Suche, Seiten. Mit Konto zusätzlich `myVote`. */
export default defineEventHandler((event) => {
  const viewer = publicIssueRead(event)
  const q = queryWith(event, issueListQuery)
  return listIssues(useCtx(), { ...q, view: undefined, priority: undefined }, viewer)
})
