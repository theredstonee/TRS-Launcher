import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { created, readJson } from '../../../lib/http'
import { createIssueBody, requireIssueWriter } from '../../../lib/issue-http'
import { createIssue } from '../../../lib/issues'

/** Neues Issue (§28.3) – Website-Sitzung oder Bearer-Token (z. B. „Bug melden“ im TRS Client). */
export default defineEventHandler(async (event) => {
  const viewer = requireIssueWriter(event, 'issueCreateUser')
  const body = await readJson(event, createIssueBody, 64 * 1024)
  return created(event, { issue: createIssue(useCtx(), viewer, body) })
})
