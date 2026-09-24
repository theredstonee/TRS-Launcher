import { defineEventHandler, setResponseHeader } from 'h3'
import { z } from 'zod'
import { notFound } from '../../../../lib/errors'
import { paramWith } from '../../../../lib/http'
import { blogPost } from '../../../../lib/site'

/** Website: ein Update-Beitrag (Text in Blöcken, Bilder von GitHub). */
export default defineEventHandler(async (event) => {
  const version = paramWith(event, 'version', z.string().regex(/^\d+\.\d+\.\d+(?:-[\w.]+)?$/))
  const post = await blogPost(version).catch(() => null)
  if (!post) throw notFound('post_not_found', 'No such post')
  setResponseHeader(event, 'Cache-Control', 'public, max-age=300')
  return { post }
})
