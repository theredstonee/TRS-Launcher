import { defineEventHandler, setResponseHeader } from 'h3'
import { z } from 'zod'
import { publicNewsFull } from '../../../../lib/blog'
import { useCtx } from '../../../../lib/context'
import { queryWith } from '../../../../lib/http'

const query = z.object({ limit: z.coerce.number().int().min(1).max(20).default(10) })

/**
 * Die neuesten News-Beiträge samt Text (§30.2) – für die Neuigkeiten im Launcher. Bild-Adressen sind relativ zur
 * Website (`/v1/site/blog/media/…`).
 */
export default defineEventHandler((event) => {
  const { limit } = queryWith(event, query)
  setResponseHeader(event, 'Cache-Control', 'public, max-age=300')
  return { posts: publicNewsFull(useCtx(), limit) }
})
