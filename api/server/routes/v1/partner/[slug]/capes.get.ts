import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { paramWith, requireWebOrUser } from '../../../../lib/http'
import { PARTNER_SLUG, selectableCapes } from '../../../../lib/partners'

/** Als Partner-Umhang wählbare eigene Umhänge von Inhaber und Mitgliedern (§44.4, Recht `cosmetics`). */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event)
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  return selectableCapes(useCtx(), auth.uuid, slug)
})
