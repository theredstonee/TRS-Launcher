import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, requireStaff } from '../../../../lib/http'
import { teamPrefsView } from '../../../../lib/mail/team-notify'
import { RULES } from '../../../../lib/ratelimit'

/** Eigene Team-Benachrichtigungen (§40.6): Adresse, Arten, Sprache – jedes Team-Mitglied für sich. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event)
  limit(`adminNl:${staff.uuid}`, RULES.adminNewsletter)
  const ctx = useCtx()
  return { notifications: teamPrefsView(ctx, staff), mailAvailable: ctx.mail.available }
})
