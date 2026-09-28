import { defineEventHandler, setResponseHeader } from 'h3'
import { publicNews } from '../../../../lib/blog'
import { useCtx } from '../../../../lib/context'
import { blogPosts } from '../../../../lib/site'

/**
 * Website: alle Update-Beiträge (neueste zuerst) aus CHANGELOG.md in `posts` (unverändert) und die News-Beiträge des
 * Teams in `news` (§30.1). Die Seite mischt beides nach Datum.
 */
export default defineEventHandler(async (event) => {
  setResponseHeader(event, 'Cache-Control', 'public, max-age=300')
  let news: ReturnType<typeof publicNews> = []
  try {
    news = publicNews(useCtx())
  } catch (err) {
    console.error('[trs-api] blog news could not be read', (err as Error).message)
  }
  return { posts: await blogPosts().catch(() => []), news }
})
