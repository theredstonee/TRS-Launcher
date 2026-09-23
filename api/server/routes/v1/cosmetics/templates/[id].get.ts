import { defineEventHandler, setResponseHeader } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { notFound } from '../../../../lib/errors'
import { paramWith, queryWith, sendPng } from '../../../../lib/http'
import { sha256Hex } from '../../../../lib/ids'
import { guideQuery } from '../../../../lib/schemas'
import { guidePng, TEMPLATE_ID } from '../../../../lib/templates'

/**
 * `GET /v1/cosmetics/templates/{id}`             → eine Vorlage (JSON)
 * `GET /v1/cosmetics/templates/{id}.png?scale=k` → Mal-Vorlage: benutzte Texturbereiche farbig (öffentlich)
 */
export default defineEventHandler((event) => {
  const raw = paramWith(event, 'id', z.string().max(40))
  const isPng = raw.endsWith('.png')
  const id = isPng ? raw.slice(0, -4) : raw
  const t = TEMPLATE_ID.test(id) ? useCtx().templates.get(id) : undefined
  if (!t) throw notFound('template_not_found', 'Template not found')
  if (!isPng) {
    setResponseHeader(event, 'Cache-Control', 'public, max-age=300')
    return { template: t }
  }
  const { scale } = queryWith(event, guideQuery)
  const png = guidePng(t, scale)
  return sendPng(event, `${id}-guide.png`, { png, sha256: sha256Hex(png), public: true })
})
