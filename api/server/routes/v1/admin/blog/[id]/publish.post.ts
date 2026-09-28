import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { blogPublishBody, publishPost } from '../../../../../lib/blog'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Veröffentlichen (`at` fehlt/Vergangenheit = sofort) oder planen (`at` in der Zukunft), §30.3. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'blog.publish')
  limit(`adminBlog:${staff.uuid}`, RULES.adminBlog)
  const id = paramWith(event, 'id', z.string().max(40))
  const body = await readJson(event, blogPublishBody)
  return { post: publishPost(useCtx(), staff, id, body) }
})
