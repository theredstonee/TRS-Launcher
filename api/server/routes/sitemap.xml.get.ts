import { defineEventHandler, setResponseHeaders } from 'h3'
import { useRuntimeConfig } from '#imports'
import { buildSitemap } from '../../shared/seo'
import { publicNews } from '../lib/blog'
import { publicJobs } from '../lib/applications'
import { circuitIndex } from '../lib/circuits'
import { useCtx } from '../lib/context'
import { docsRoutes } from '../lib/docs'
import { blogPosts } from '../lib/site'

/**
 * Website: Sitemap mit allen Seiten, Update-Beiträgen und News-Beiträgen (§30, nur übersetzte Sprachen), je Sprache
 * mit hreflang-Alternativen (shared/seo.ts), dazu die Seiten der Dokumentation unter /docs (Routenliste aus dem Docs-Build).
 */
export default defineEventHandler(async (event) => {
  const ctx = useCtx()
  const base = ctx.config.siteUrl
  const jobs = publicJobs(ctx).map((j) => ({ id: j.id, updatedAt: j.updatedAt }))
  const circuits = circuitIndex(ctx).index.circuits.map((c) => ({ id: c.id, updatedAt: c.updatedAt }))
  const news = publicNews(ctx).map((n) => ({ slug: n.slug, updatedAt: n.updatedAt, langs: n.langs }))
  const posts = await blogPosts().catch(() => [])
  const docs = await docsRoutes()
  const buildTime = String(useRuntimeConfig().buildTime ?? '') || null
  setResponseHeaders(event, { 'Content-Type': 'application/xml; charset=utf-8', 'Cache-Control': 'public, max-age=3600' })
  return buildSitemap(base, posts, buildTime, jobs, circuits, news, docs)
})
