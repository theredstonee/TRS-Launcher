import { defineEventHandler } from 'h3'
import { blogAuthors } from '../../../../lib/blog'
import { useCtx } from '../../../../lib/context'
import { requireStaff } from '../../../../lib/http'

/** Mögliche Autoren eines Beitrags (§30.3): Team-Mitglieder mit Konto. */
export default defineEventHandler((event) => {
  requireStaff(event, ['blog.write', 'blog.publish'])
  return { authors: blogAuthors(useCtx()) }
})
