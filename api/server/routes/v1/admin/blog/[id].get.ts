import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { adminView, getPostRow } from '../../../../lib/blog'
import { useCtx } from '../../../../lib/context'
import { paramWith, requireStaff } from '../../../../lib/http'

/** Ein Beitrag mit allen Sprachen, Bildern und `rev` für den Editor (§30.3). */
export default defineEventHandler((event) => {
  requireStaff(event, ['blog.write', 'blog.publish'])
  const id = paramWith(event, 'id', z.string().max(40))
  const ctx = useCtx()
  return { post: adminView(ctx, getPostRow(ctx, id)) }
})
