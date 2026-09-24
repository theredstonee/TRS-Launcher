import { defineEventHandler, setResponseHeaders } from 'h3'
import { useCtx } from '../lib/context'
import { blogPosts } from '../lib/site'

const PAGES = ['/', '/download', '/blog', '/capes', '/faq', '/privacy']

function escapeXml(s: string): string {
  return s.replace(/[<>&'"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', "'": '&apos;', '"': '&quot;' })[c]!)
}

/** Website: Sitemap mit allen Seiten und Update-Beiträgen. */
export default defineEventHandler(async (event) => {
  const base = useCtx().config.siteUrl
  const posts = await blogPosts().catch(() => [])
  const urls = [
    ...PAGES.map((p) => `<url><loc>${escapeXml(base + p)}</loc></url>`),
    ...posts.map((p) => `<url><loc>${escapeXml(`${base}/blog/${p.version}`)}</loc>${p.date ? `<lastmod>${p.date}</lastmod>` : ''}</url>`),
  ]
  setResponseHeaders(event, { 'Content-Type': 'application/xml; charset=utf-8', 'Cache-Control': 'public, max-age=3600' })
  return `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls.join('\n')}\n</urlset>\n`
})
