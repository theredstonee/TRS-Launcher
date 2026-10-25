import { defineEventHandler, getHeader } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { actingAs } from '../../../../../../lib/creator-http'
import { PET_IMAGE_MAX_BYTES, uploadPetRequestImage } from '../../../../../../lib/creator-pets'
import { unsupportedMedia } from '../../../../../../lib/errors'
import { paramWith, readLimited, requireWebOrUser } from '../../../../../../lib/http'

const TYPES = new Set(['image/png', 'image/jpeg'])

/**
 * Referenzbild zum offenen Haustier-Wunsch (§35.13): roher Body, `Content-Type: image/png` oder `image/jpeg`, höchstens
 * 2 MiB; die Magic Bytes müssen passen, das Bild wird neu kodiert. 5 Bilder / 24 h je Creator. → `{ request }`
 */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  const id = paramWith(event, 'id', z.coerce.number().int().min(1).max(Number.MAX_SAFE_INTEGER))
  const type = (getHeader(event, 'content-type') ?? '').split(';')[0]!.trim().toLowerCase()
  if (!TYPES.has(type)) throw unsupportedMedia('Content-Type must be image/png or image/jpeg')
  const as = actingAs(event)
  const body = await readLimited(event, PET_IMAGE_MAX_BYTES)
  return uploadPetRequestImage(useCtx(), auth.uuid, id, body, type, as)
})
