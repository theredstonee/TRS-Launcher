import { defineEventHandler, setResponseHeaders } from 'h3'
import { publicNews } from '../lib/blog'
import { useCtx } from '../lib/context'
import { blogPosts } from '../lib/site'

function escapeXml(s: string): string {
  return s.replace(/[<>&'"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', "'": '&apos;', '"': '&quot;' })[c]!)
}

interface FeedItem {
  title: string
  link: string
  at: number
  description: string
}

/** Website: RSS-Feed der Update- und News-Beiträge (Englisch, neueste zuerst). */
export default defineEventHandler(async (event) => {
  const ctx = useCtx()
  const base = ctx.config.siteUrl
  const updates: FeedItem[] = (await blogPosts().catch(() => [])).map((p) => ({
    title: p.title ? `${p.title.en} (v${p.version})` : `TRS Launcher ${p.version}`,
    link: `${base}/blog/${p.version}`,
    at: p.date ? Date.parse(`${p.date}T12:00:00Z`) : 0,
    description: p.headlines.en.join(' · '),
  }))
  const news: FeedItem[] = publicNews(ctx).map((n) => ({
    title: n.title.en ?? n.slug,
    link: `${base}/blog/${n.slug}`,
    at: Date.parse(n.publishedAt),
    description: n.summary.en ?? '',
  }))
  const items = [...updates, ...news]
    .sort((a, b) => b.at - a.at)
    .slice(0, 30)
    .map((i) => {
      const date = i.at ? `<pubDate>${new Date(i.at).toUTCString()}</pubDate>` : ''
      return `<item><title>${escapeXml(i.title)}</title><link>${escapeXml(i.link)}</link><guid>${escapeXml(i.link)}</guid>${date}<description>${escapeXml(i.description)}</description></item>`
    })
  setResponseHeaders(event, { 'Content-Type': 'application/rss+xml; charset=utf-8', 'Cache-Control': 'public, max-age=900' })
  return `<?xml version="1.0" encoding="UTF-8"?>\n<rss version="2.0"><channel><title>TRS Launcher</title><link>${escapeXml(base)}</link><description>Updates and news of the TRS Launcher</description><language>en</language>\n${items.join('\n')}\n</channel></rss>\n`
})
