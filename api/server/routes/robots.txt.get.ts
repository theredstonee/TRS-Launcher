import { defineEventHandler, setResponseHeaders } from 'h3'
import { useCtx } from '../lib/context'

/** Website: alles außer API und Admin darf indexiert werden. */
export default defineEventHandler((event) => {
  setResponseHeaders(event, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'public, max-age=86400' })
  return `User-agent: *\nDisallow: /v1/\nDisallow: /admin\n\nSitemap: ${useCtx().config.siteUrl}/sitemap.xml\n`
})
