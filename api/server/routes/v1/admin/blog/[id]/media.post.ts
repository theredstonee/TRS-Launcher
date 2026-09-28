import { defineEventHandler, getHeader } from 'h3'
import { z } from 'zod'
import { MAX_BLOG_IMAGE_BYTES, uploadMedia } from '../../../../../lib/blog'
import { useCtx } from '../../../../../lib/context'
import { unsupportedMedia } from '../../../../../lib/errors'
import { created, limit, paramWith, readLimited, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

const TYPES = new Set(['image/png', 'image/jpeg', 'image/webp'])

/**
 * Bild für einen Beitrag hochladen (roh, PNG/JPEG/WebP ≤ 8 MiB, §30.3) → neu kodiert (ohne Metadaten, ≤ 2400 px),
 * dazu eine Vorschau. Öffentlich erst, wenn der Beitrag öffentlich ist.
 */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'blog.write')
  limit(`adminBlogUpload:${staff.uuid}`, RULES.adminBlogUpload)
  const id = paramWith(event, 'id', z.string().max(40))
  const type = (getHeader(event, 'content-type') ?? '').split(';')[0]!.trim().toLowerCase()
  if (!TYPES.has(type)) throw unsupportedMedia('Content-Type must be image/png, image/jpeg or image/webp')
  const body = await readLimited(event, MAX_BLOG_IMAGE_BYTES)
  return created(event, { media: await uploadMedia(useCtx(), staff, id, body, type) })
})
