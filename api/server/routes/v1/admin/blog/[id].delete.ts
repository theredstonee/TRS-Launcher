import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { deletePost } from '../../../../lib/blog'
import { useCtx } from '../../../../lib/context'
import { limit, noContent, paramWith, requireStaff } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Beitrag samt Bildern löschen (§30.3). Veröffentlichte/geplante Beiträge brauchen `blog.publish`. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'blog.write')
  limit(`adminBlog:${staff.uuid}`, RULES.adminBlog)
  const id = paramWith(event, 'id', z.string().max(40))
  deletePost(useCtx(), staff, id)
  return noContent(event)
})
