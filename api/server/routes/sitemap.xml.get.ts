import { defineEventHandler, setResponseHeaders } from 'h3'
import { useRuntimeConfig } from '#imports'
import { buildSitemap } from '../../shared/seo'
import { publicJobs } from '../lib/applications'
import { useCtx } from '../lib/context'
import { blogPosts } from '../lib/site'

/** Website: Sitemap mit allen Seiten und Update-Beiträgen, je Sprache mit hreflang-Alternativen (shared/seo.ts). */
export default defineEventHandler(async (event) => {
  const ctx = useCtx()
  const base = ctx.config.siteUrl
  const jobs = publicJobs(ctx).map((j) => ({ id: j.id, updatedAt: j.updatedAt }))
  const posts = await blogPosts().catch(() => [])
  const buildTime = String(useRuntimeConfig().buildTime ?? '') || null
  setResponseHeaders(event, { 'Content-Type': 'application/xml; charset=utf-8', 'Cache-Control': 'public, max-age=3600' })
  return buildSitemap(base, posts, buildTime, jobs)
})
