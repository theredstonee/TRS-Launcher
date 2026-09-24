import { defineEventHandler, setResponseHeaders } from 'h3'
import { useCtx } from '../lib/context'
import { blogPosts } from '../lib/site'

function escapeXml(s: string): string {
  return s.replace(/[<>&'"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', "'": '&apos;', '"': '&quot;' })[c]!)
}

/** Website: RSS-Feed der Update-Beiträge (Englisch). */
export default defineEventHandler(async (event) => {
  const base = useCtx().config.siteUrl
  const posts = (await blogPosts().catch(() => [])).slice(0, 30)
  const items = posts.map((p) => {
    const link = `${base}/blog/${p.version}`
    const title = p.title ? `${p.title.en} (v${p.version})` : `TRS Launcher ${p.version}`
    const date = p.date ? `<pubDate>${new Date(`${p.date}T12:00:00Z`).toUTCString()}</pubDate>` : ''
    return `<item><title>${escapeXml(title)}</title><link>${escapeXml(link)}</link><guid>${escapeXml(link)}</guid>${date}<description>${escapeXml(p.headlines.en.join(' · '))}</description></item>`
  })
  setResponseHeaders(event, { 'Content-Type': 'application/rss+xml; charset=utf-8', 'Cache-Control': 'public, max-age=900' })
  return `<?xml version="1.0" encoding="UTF-8"?>\n<rss version="2.0"><channel><title>TRS Launcher</title><link>${escapeXml(base)}</link><description>Updates of the TRS Launcher</description><language>en</language>\n${items.join('\n')}\n</channel></rss>\n`
})
