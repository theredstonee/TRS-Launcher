import { defineEventHandler } from 'h3'
import { mySubmissions, submissionLimits } from '../../../lib/circuits'
import { useCtx } from '../../../lib/context'
import { requireWebOrUser } from '../../../lib/http'

/** Eigene Einreichungen (§25.5) mit Status und Grund der Ablehnung, dazu die Tagesgrenze (Website oder Launcher/Client). */
export default defineEventHandler((event) => {
  const me = requireWebOrUser(event)
  const ctx = useCtx()
  return { submissions: mySubmissions(ctx, me.uuid), limits: submissionLimits(ctx, me.uuid) }
})
