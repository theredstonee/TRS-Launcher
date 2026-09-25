import { defineEventHandler, setResponseHeaders } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { notFound } from '../../../../../lib/errors'
import { paramWith, requireUser } from '../../../../../lib/http'
import { SYNC_SKIN_ID } from '../../../../../lib/schemas'
import { readSkinPng } from '../../../../../lib/sync'

/** `GET /v1/me/sync/skins/{id}.png` – Bild eines eigenen Skins, nie zwischengespeichert. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'sync')
  const raw = paramWith(event, 'id', z.string().max(16))
  const id = raw.endsWith('.png') ? raw.slice(0, -4) : ''
  if (!SYNC_SKIN_ID.test(id)) throw notFound('skin_not_found', 'Skin not found')
  const skin = readSkinPng(useCtx(), auth.uuid, id)
  setResponseHeaders(event, {
    'Content-Type': 'image/png',
    'Content-Disposition': `inline; filename="${id}.png"`,
    'Cache-Control': 'private, no-store',
    ETag: `"${skin.sha256}"`,
  })
  return skin.png
})
