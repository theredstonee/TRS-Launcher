import { defineEventHandler, setResponseHeader } from 'h3'
import { z } from 'zod'
import { BLOG_SLUG, publicNewsPost } from '../../../../lib/blog'
import { useCtx } from '../../../../lib/context'
import { notFound } from '../../../../lib/errors'
import { paramWith } from '../../../../lib/http'

/** Website: ein News-Beitrag (§30.2) – nur öffentliche (veröffentlicht, Zeitpunkt erreicht). */
export default defineEventHandler((event) => {
  const slug = paramWith(event, 'slug', z.string().max(80).regex(BLOG_SLUG))
  const post = publicNewsPost(useCtx(), slug)
  if (!post) throw notFound('post_not_found', 'No such post')
  setResponseHeader(event, 'Cache-Control', 'public, max-age=300')
  return { post }
})
