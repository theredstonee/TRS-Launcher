import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { paramWith, requireWebOrUser, sendPng } from '../../../../../../lib/http'
import { memberRequestImage, PARTNER_SLUG } from '../../../../../../lib/partners'

/** Bild eines offenen Logo-/Umhang-Antrags (nie cachebar). */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event)
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  const id = paramWith(event, 'id', z.coerce.number().int().min(1).max(Number.MAX_SAFE_INTEGER))
  return sendPng(event, `request-${id}.png`, memberRequestImage(useCtx(), auth.uuid, slug, id))
})
