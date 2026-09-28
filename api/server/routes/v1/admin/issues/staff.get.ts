import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { requireStaff } from '../../../../lib/http'
import { assigneeCandidates } from '../../../../lib/issues'

/** Wer kann zuständig sein (Team-Mitglieder mit issues.manage). */
export default defineEventHandler((event) => {
  requireStaff(event, 'issues.manage')
  return { staff: assigneeCandidates(useCtx()) }
})
