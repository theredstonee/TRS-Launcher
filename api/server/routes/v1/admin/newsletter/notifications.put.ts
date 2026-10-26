import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { TEAM_NOTIFY_KINDS } from '../../../../../shared/newsletter'
import { audit } from '../../../../lib/audit'
import { useCtx } from '../../../../lib/context'
import { limit, readJson, requireStaff } from '../../../../lib/http'
import { normalizeEmail } from '../../../../lib/mail/subscribers'
import { setTeamPrefs } from '../../../../lib/mail/team-notify'
import { RULES } from '../../../../lib/ratelimit'

const Body = z.strictObject({
  /** Neue Adresse (Bestätigungs-Mail), `null` = Adresse löschen, weglassen = unverändert. */
  email: z.string().max(320).nullable().optional(),
  kinds: z.array(z.enum(TEAM_NOTIFY_KINDS)).max(TEAM_NOTIFY_KINDS.length),
  lang: z.enum(['de', 'en', 'es']),
})

/** Eigene Team-Benachrichtigungen speichern (§40.6). → `{ notifications, confirmationSent }`. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event)
  limit(`teamNotify:${staff.uuid}`, RULES.teamNotifyUser)
  const body = await readJson(event, Body)
  const ctx = useCtx()
  const r = setTeamPrefs(ctx, staff, body, normalizeEmail)
  audit(ctx, staff.uuid, 'newsletter.notify.update', staff.uuid, `${r.view.kinds.join(',') || '-'}${r.confirmationSent ? ' (new address)' : ''}`)
  return { notifications: r.view, confirmationSent: r.confirmationSent }
})
