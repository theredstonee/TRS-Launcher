import { defineEventHandler } from 'h3'
import { adminList } from '../../../../lib/blog'
import { useCtx } from '../../../../lib/context'
import { requireStaff } from '../../../../lib/http'

/** Blog im Team-Bereich (§30.3): alle Beiträge (Entwürfe zuerst, dann nach Datum). */
export default defineEventHandler((event) => {
  requireStaff(event, ['blog.write', 'blog.publish'])
  return { posts: adminList(useCtx()) }
})
