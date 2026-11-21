import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { paramWith, readJson, requireWebOrUser } from '../../../../../lib/http'
import { PARTNER_SLUG, updateMember } from '../../../../../lib/partners'

/** Rechte eines Mitglieds ändern (Inhaber oder Team-Modus): `{ rights }` → `{ members }`. */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  const uuid = paramWith(event, 'uuid', z.string().regex(/^[0-9a-f]{32}$/))
  return updateMember(useCtx(), auth.uuid, slug, uuid, await readJson(event, z.unknown()))
})
