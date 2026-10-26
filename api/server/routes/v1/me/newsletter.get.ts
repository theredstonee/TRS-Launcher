import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { requireUser } from '../../../lib/http'
import { myNewsletter } from '../../../lib/mail/subscribers'

/** Launcher: eigener Newsletter-Status (§40.3) → `{ newsletter: { status, email, lang, source, confirmedAt, updatedAt } }`. */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  return { newsletter: myNewsletter(useCtx(), auth.uuid) }
})
