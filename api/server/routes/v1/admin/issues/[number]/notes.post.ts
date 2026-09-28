import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { readJson, requireStaff } from '../../../../../lib/http'
import { issueNumberParam, noteBody } from '../../../../../lib/issue-http'
import { addNote } from '../../../../../lib/issues'

/** Interne Notiz (nie öffentlich). */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'issues.manage')
  const number = issueNumberParam(event)
  const body = await readJson(event, noteBody)
  return { notes: addNote(useCtx(), staff, number, body.text) }
})
