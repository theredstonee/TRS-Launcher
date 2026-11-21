import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { created, paramWith, readJson, requireWebOrUser } from '../../../../../lib/http'
import { inviteMember, PARTNER_SLUG } from '../../../../../lib/partners'

/** Mitglied einladen (nur Inhaber): `{ uuid | name, rights }` → 201 `{ members }`. Muss angenommen werden. */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  return created(event, inviteMember(useCtx(), auth.uuid, slug, await readJson(event, z.unknown())))
})
