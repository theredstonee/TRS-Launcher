import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { created, limit, readJson, requireStaff } from '../../../../../lib/http'
import { normalizeEmail } from '../../../../../lib/mail/subscribers'
import { KEY_PERMISSIONS, createKey } from '../../../../../lib/mail/keys'
import { RULES } from '../../../../../lib/ratelimit'
import { badRequest } from '../../../../../lib/errors'

const Body = z.strictObject({
  name: z.string().trim().min(1).max(60),
  permissions: z.array(z.enum(KEY_PERMISSIONS)).min(1).max(2),
  testEmail: z.string().max(320).nullable().optional(),
})

/** Schlüssel anlegen (§40.5) – nur Owner/Admin. Der Klartext `token` kommt NUR in dieser Antwort. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event)
  limit(`adminNl:${staff.uuid}`, RULES.adminNewsletter)
  const body = await readJson(event, Body)
  let testEmail: string | null = null
  if (body.testEmail) {
    testEmail = normalizeEmail(body.testEmail)
    if (!testEmail) throw badRequest('invalid_email', 'This e-mail address is not valid')
  }
  return created(event, createKey(useCtx(), staff, { name: body.name, permissions: body.permissions, testEmail }))
})
