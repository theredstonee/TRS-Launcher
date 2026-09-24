import { defineEventHandler, setResponseHeader } from 'h3'
import { blogPosts } from '../../../../lib/site'

/** Website: alle Update-Beiträge (neueste zuerst) aus CHANGELOG.md. */
export default defineEventHandler(async (event) => {
  setResponseHeader(event, 'Cache-Control', 'public, max-age=300')
  return { posts: await blogPosts().catch(() => []) }
})
