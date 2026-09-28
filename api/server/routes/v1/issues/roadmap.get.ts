import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { publicIssueRead } from '../../../lib/issue-http'
import { roadmap } from '../../../lib/issues'

/** Roadmap (§28.3): Geplant, In Arbeit (inkl. In Prüfung), Fertig (letzte 30 Tage). */
export default defineEventHandler((event) => {
  const viewer = publicIssueRead(event)
  return roadmap(useCtx(), viewer)
})
