import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { noContent, paramWith, requireWebOrUser } from '../../../../../lib/http'
import { PARTNER_SLUG, removeMember } from '../../../../../lib/partners'

/** Mitglied entfernen bzw. Einladung zurückziehen. 204. */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event, 'write')
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  removeMember(useCtx(), auth.uuid, slug, paramWith(event, 'uuid', z.string().regex(/^[0-9a-f]{32}$/)))
  return noContent(event)
})
