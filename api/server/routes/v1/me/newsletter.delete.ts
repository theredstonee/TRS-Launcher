import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { limit, requireUser } from '../../../lib/http'
import { deleteMyNewsletter } from '../../../lib/mail/subscribers'
import { RULES } from '../../../lib/ratelimit'

/** Launcher: Newsletter abbestellen (§40.3) → `{ newsletter }` mit Status `unsubscribed` (bzw. `none`). */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`nlMe:${auth.uuid}`, RULES.newsletterMeUser)
  return { newsletter: deleteMyNewsletter(useCtx(), auth.uuid) }
})
