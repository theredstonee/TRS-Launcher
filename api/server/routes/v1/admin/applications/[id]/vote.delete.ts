import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { removeVote } from '../../../../../lib/applications'
import { useCtx } from '../../../../../lib/context'
import { paramWith, requireStaff } from '../../../../../lib/http'

/** Eigene Stimme zurücknehmen. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'applications.review')
  return { application: removeVote(useCtx(), staff, paramWith(event, 'id', z.string().regex(/^a[0-9a-f]{16}$/))) }
})
