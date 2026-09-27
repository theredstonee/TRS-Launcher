import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { adminSubmissionView, freeCircuitId, getSubmission } from '../../../../../lib/circuits'
import { useCtx } from '../../../../../lib/context'
import { notFound } from '../../../../../lib/errors'
import { paramWith, requireStaff } from '../../../../../lib/http'

/** Team (§25.5): eine Einreichung mit Schaltung und Vorschlag für die ID beim Annehmen. */
export default defineEventHandler((event) => {
  requireStaff(event, 'circuits.manage')
  const id = paramWith(event, 'id', z.string().regex(/^cs[0-9a-f]{16}$/))
  const ctx = useCtx()
  const s = getSubmission(ctx, id)
  if (!s) throw notFound('submission_not_found', 'Submission not found')
  return { submission: adminSubmissionView(ctx, s), suggestedId: s.status === 'pending' ? freeCircuitId(ctx, s.title) : null }
})
