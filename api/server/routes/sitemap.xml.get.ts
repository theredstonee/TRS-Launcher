import { defineEventHandler, setResponseHeaders } from 'h3'
import { useRuntimeConfig } from '#imports'
import { buildSitemap } from '../../shared/seo'
import { useCtx } from '../lib/context'
import { blogPosts } from '../lib/site'

/** Website: Sitemap mit allen Seiten und Update-Beiträgen, je Sprache mit hreflang-Alternativen (shared/seo.ts). */
export default defineEventHandler(async (event) => {
  const base = useCtx().config.siteUrl
  const posts = await blogPosts().catch(() => [])
  const buildTime = String(useRuntimeConfig().buildTime ?? '') || null
  setResponseHeaders(event, { 'Content-Type': 'application/xml; charset=utf-8', 'Cache-Control': 'public, max-age=3600' })
  return buildSitemap(base, posts, buildTime)
})
