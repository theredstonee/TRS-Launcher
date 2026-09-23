import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { cosmeticView, getCosmetic, readCosmeticTexture, renderable, visibleTo } from '../../../lib/cosmetics'
import { notFound } from '../../../lib/errors'
import { optionalUser, paramWith, sendPng } from '../../../lib/http'
import { COSMETIC_ID } from '../../../lib/ids'

/**
 * `GET /v1/cosmetics/{id}.png` → Textur (öffentlich nur freigegebene; wartende nur für Besitzer/Admins).
 * `GET /v1/cosmetics/{id}`     → Metadaten.
 */
export default defineEventHandler((event) => {
  const raw = paramWith(event, 'id', z.string().max(48))
  const isPng = raw.endsWith('.png')
  const id = isPng ? raw.slice(0, -4) : raw
  if (!COSMETIC_ID.test(id)) throw notFound('cosmetic_not_found', 'Cosmetic not found')
  const ctx = useCtx()
  const viewer = optionalUser(event)
  const who = viewer ? { uuid: viewer.uuid, admin: viewer.admin } : null

  if (!isPng) {
    const c = getCosmetic(ctx, id)
    if (!c || !visibleTo(c, who) || !renderable(ctx, c)) throw notFound('cosmetic_not_found', 'Cosmetic not found')
    return { cosmetic: cosmeticView(ctx, c) }
  }
  return sendPng(event, `${id}.png`, readCosmeticTexture(ctx, id, who))
})
