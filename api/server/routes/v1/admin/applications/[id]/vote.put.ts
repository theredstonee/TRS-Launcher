import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { voteApplication } from '../../../../../lib/applications'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Eigene Stimme (+1/-1 mit Kommentar) setzen oder ändern. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'applications.review')
  limit(`admin-app:${staff.uuid}`, RULES.adminApplication)
  const id = paramWith(event, 'id', z.string().regex(/^a[0-9a-f]{16}$/))
  const body = await readJson(event, z.unknown())
  return { application: voteApplication(useCtx(), staff, id, body) }
})
