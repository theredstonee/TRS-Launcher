import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { deleteMedia } from '../../../../../../lib/blog'
import { useCtx } from '../../../../../../lib/context'
import { limit, noContent, paramWith, requireStaff } from '../../../../../../lib/http'
import { RULES } from '../../../../../../lib/ratelimit'

/** Bild eines Beitrags löschen (§30.3); war es das Titelbild, hat der Beitrag danach keins. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'blog.write')
  limit(`adminBlog:${staff.uuid}`, RULES.adminBlog)
  const id = paramWith(event, 'id', z.string().max(40))
  const mediaId = paramWith(event, 'mediaId', z.string().max(40))
  deleteMedia(useCtx(), staff, id, mediaId)
  return noContent(event)
})
