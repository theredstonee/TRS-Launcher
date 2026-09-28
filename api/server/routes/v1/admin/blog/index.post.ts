import { defineEventHandler } from 'h3'
import { blogCreateBody, createPost } from '../../../../lib/blog'
import { useCtx } from '../../../../lib/context'
import { created, limit, readJson, requireStaff } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Neuer Entwurf (§30.3). Slug fehlt → aus dem englischen Titel. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'blog.write')
  limit(`adminBlog:${staff.uuid}`, RULES.adminBlog)
  const body = await readJson(event, blogCreateBody)
  return created(event, { post: createPost(useCtx(), staff, body) })
})
