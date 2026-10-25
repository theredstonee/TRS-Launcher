import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { readPetRequestImage } from '../../../lib/creator-pets'
import { paramWith, sendCached, textureViewer } from '../../../lib/http'

/**
 * `GET /v1/creator-pet-images/{sha256}.png|.jpg` (§35.13): Referenzbild eines Haustier-Wunsches – nur für den Creator
 * selbst und Team-Mitglieder mit Prüfrecht (Bearer oder Website-Sitzung), nie öffentlich (`private, no-store`).
 */
export default defineEventHandler((event) => {
  const file = paramWith(event, 'file', z.string().regex(/^[0-9a-f]{64}\.(png|jpg)$/))
  const sha = file.slice(0, 64)
  const ext = file.endsWith('.png') ? 'png' : 'jpg'
  const img = readPetRequestImage(useCtx(), sha, ext, textureViewer(event))
  return sendCached(event, `${sha.slice(0, 12)}.${ext}`, img.mime, { body: img.body, sha256: img.sha256, public: false })
})
