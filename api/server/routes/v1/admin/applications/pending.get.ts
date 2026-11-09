import { defineEventHandler } from 'h3'
import { pendingApplicationReviews } from '../../../../lib/applications'
import { useCtx } from '../../../../lib/context'
import { requireStaff } from '../../../../lib/http'

export default defineEventHandler((event) => ({
  pendingConfirmations: pendingApplicationReviews(useCtx(), requireStaff(event, 'applications.view')),
}))