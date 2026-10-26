import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, requireStaff } from '../../../../../lib/http'
import { revokeKey } from '../../../../../lib/mail/keys'
import { RULES } from '../../../../../lib/ratelimit'

/** Schlüssel widerrufen (§40.5) – nur Owner/Admin; gilt sofort. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event)
  limit(`adminNl:${staff.uuid}`, RULES.adminNewsletter)
  const id = paramWith(event, 'id', z.coerce.number().int().positive())
  return { key: revokeKey(useCtx(), staff, id) }
})
