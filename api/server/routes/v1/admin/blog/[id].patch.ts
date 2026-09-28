import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { blogPatchBody, MAX_BODY, updatePost } from '../../../../lib/blog'
import { useCtx } from '../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/**
 * Beitrag speichern (§30.3): Slug, Texte je Sprache (ganz leere Sprache = entfernen), Titelbild, Autor. `rev` muss
 * passen (sonst `409 stale`). Veröffentlichte/geplante Beiträge brauchen `blog.publish`.
 */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'blog.write')
  limit(`adminBlog:${staff.uuid}`, RULES.adminBlog)
  const id = paramWith(event, 'id', z.string().max(40))
  // Drei Sprachen × Markdown (UTF-8 bis zu 3 Byte je Zeichen) + Rest.
  const body = await readJson(event, blogPatchBody, 3 * 3 * (MAX_BODY + 2000) + 16 * 1024)
  return { post: updatePost(useCtx(), staff, id, body) }
})
