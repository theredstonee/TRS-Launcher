import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { blogRevBody, unpublishPost } from '../../../../../lib/blog'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Zurück zum Entwurf – nicht mehr öffentlich, eine Planung entfällt (§30.3). */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'blog.publish')
  limit(`adminBlog:${staff.uuid}`, RULES.adminBlog)
  const id = paramWith(event, 'id', z.string().max(40))
  const { rev } = await readJson(event, blogRevBody)
  return { post: unpublishPost(useCtx(), staff, id, rev) }
})
