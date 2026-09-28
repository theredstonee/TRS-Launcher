import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { requireWebOrUser } from '../../../lib/http'
import { myIssues } from '../../../lib/issues'
import { teamOf } from '../../../lib/team'

/** Eigene und gefolgte Issues (§28.3). */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event, 'read')
  const ctx = useCtx()
  return myIssues(ctx, { uuid: auth.uuid, name: auth.name, staff: teamOf(ctx, auth.uuid) })
})
