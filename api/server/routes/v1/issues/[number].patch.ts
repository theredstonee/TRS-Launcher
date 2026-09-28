import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { readJson } from '../../../lib/http'
import { editIssueBody, issueNumberParam, requireIssueWriter } from '../../../lib/issue-http'
import { editIssue } from '../../../lib/issues'

/** Eigenes Issue bearbeiten (Titel, Beschreibung, Art) – nur solange es „Offen“ ist. */
export default defineEventHandler(async (event) => {
  const viewer = requireIssueWriter(event, 'issueEditUser')
  const number = issueNumberParam(event)
  const body = await readJson(event, editIssueBody, 64 * 1024)
  return { issue: editIssue(useCtx(), viewer, number, body) }
})
