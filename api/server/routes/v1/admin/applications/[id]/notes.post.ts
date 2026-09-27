import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { addApplicationNote } from '../../../../../lib/applications'
import { useCtx } from '../../../../../lib/context'
import { created, limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Interne Notiz (nie für den Bewerber sichtbar). 201 `{ application }`. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'applications.review')
  limit(`admin-app:${staff.uuid}`, RULES.adminApplication)
  const id = paramWith(event, 'id', z.string().regex(/^a[0-9a-f]{16}$/))
  const body = await readJson(event, z.unknown())
  return created(event, { application: addApplicationNote(useCtx(), staff, id, body) })
})
