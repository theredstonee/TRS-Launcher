import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { i18n, setLocale } from '../app/utils/i18n'
import { liveEventSchema } from '../app/utils/chat'
import {
  appealProblem,
  bySeverity,
  endText,
  mySanctionsSchema,
  sanctionErrorText,
  sanctionFromParams,
  sanctionIsActive,
  type MySanction,
} from '../app/utils/sanctions'
import {
  draftMinutes,
  draftProblem,
  draftToInput,
  durationAllowed,
  emptyDraft,
  limitsByRole,
  playerFileSchema,
  dashboardSchema,
  adminSanctionSchema,
} from '../app/utils/team'

// Moderation v2 (API §22): Texte der Strafen, Einspruch-Regeln, Fehlerparameter
// aus dem Kern, Rechte-Grenzen im Formular und die Schemas der Team-Antworten.

const future = new Date(Date.now() + 5 * 3_600_000).toISOString()
const sanction = (extra: Partial<MySanction> = {}): MySanction => ({
  id: 7,
  kind: 'chat_mute',
  reasonCode: 'spam',
  reason: 'Links im Chat',
  startsAt: '2026-09-26T10:00:00.000Z',
  endsAt: future,
  status: 'active',
  liftedAt: null,
  appeal: null,
  appealable: true,
  ...extra,
})

let before: string
beforeAll(async () => {
  before = i18n.global.locale.value
  await setLocale('en')
})
afterAll(async () => {
  await setLocale(before)
})

describe('Eigene Strafen', () => {
  it('Ende in Worten: dauerhaft, bis zur Prüfung, relativ + Datum', () => {
    expect(endText(sanction({ endsAt: null }))).toBe('permanent')
    expect(endText(sanction({ endsAt: null, reasonCode: 'auto_reports' }))).toBe('until a moderator has reviewed it')
    expect(endText(sanction())).toMatch(/^ends in 5 hours \(/)
    expect(endText(sanction({ status: 'lifted', liftedAt: '2026-09-26T12:00:00.000Z' }))).toMatch(/^lifted on /)
    expect(endText(sanction({ status: 'expired', endsAt: '2026-09-20T12:00:00.000Z' }))).toMatch(/^ended on /)
  })

  it('abgelaufene Enden zählen nicht mehr, schwerste Strafe zuerst', () => {
    expect(sanctionIsActive(sanction())).toBe(true)
    expect(sanctionIsActive(sanction({ endsAt: '2020-01-01T00:00:00.000Z' }))).toBe(false)
    expect(sanctionIsActive(sanction({ status: 'lifted' }))).toBe(false)
    const list = [sanction({ id: 1, kind: 'warn' }), sanction({ id: 2, kind: 'account_ban' }), sanction({ id: 3 })]
    expect(list.sort(bySeverity).map((s) => s.id)).toEqual([2, 3, 1])
  })

  it('Einspruch: 20 bis 1000 Zeichen nach dem Trimmen', () => {
    expect(appealProblem('  zu kurz  ')).toContain('20')
    expect(appealProblem('x'.repeat(1001))).toContain('1000')
    expect(appealProblem('Das war ein Missverständnis, bitte prüft es.')).toBeNull()
    expect(appealProblem('ä'.repeat(1000))).toBeNull()
  })

  it('Fehlerparameter aus dem Kern werden zu einem verständlichen Text', () => {
    const params = { sanctionId: '7', sanctionKind: 'upload_ban', reasonCode: 'copyright', reason: 'Fremdes Logo', endsAt: future, appealable: 'true', appealStatus: '' }
    const text = sanctionErrorText('sanctioned', params)!
    expect(text).toContain('Uploads are blocked for you')
    expect(text).toContain('ends in 5 hours')
    expect(text).toContain('Copyright – “Fremdes Logo”')
    // Ältere Server ohne Details: Art aus dem Code, trotzdem kein roher Fehler.
    expect(sanctionErrorText('chat_muted', {})).toContain('You are muted in chat')
    expect(sanctionErrorText('banned', undefined)).toContain('banned')
    expect(sanctionErrorText('cape_locked', params)).toBeNull()
    const s = sanctionFromParams('banned', { ...params, sanctionKind: 'account_ban', appealStatus: 'open' })!
    expect(s.kind).toBe('account_ban')
    expect(s.appeal?.status).toBe('open')
  })

  it('Liste und Live-Ereignisse werden geprüft', () => {
    const parsed = mySanctionsSchema.parse({ active: [{ ...sanction(), reasonCode: 'brand_new' }], past: [] })
    expect(parsed.active[0]!.reasonCode).toBe('other')
    const added = liveEventSchema.safeParse({ type: 'sanction_added', sanction: sanction() })
    expect(added.success).toBe(true)
    const decided = liveEventSchema.safeParse({
      type: 'appeal_decided',
      sanctionId: 7,
      appeal: { id: 3, status: 'shortened', createdAt: 'x', decidedAt: 'y', response: 'Ok' },
      sanction: sanction({ appeal: { id: 3, status: 'shortened', createdAt: 'x', decidedAt: 'y', response: 'Ok' }, appealable: false }),
    })
    expect(decided.success).toBe(true)
    expect(liveEventSchema.safeParse({ type: 'sanction_added', sanction: { ...sanction(), kind: 'nuke' } }).success).toBe(false)
  })
})

describe('Team: Strafe vergeben', () => {
  const mod = limitsByRole.moderator
  const admin = limitsByRole.admin

  it('Moderatoren: höchstens 7 Tage, Verwarnung 30, nie dauerhaft, kein Konto-Bann', () => {
    expect(durationAllowed('7d', 'chat_mute', mod)).toBe(true)
    expect(durationAllowed('30d', 'chat_mute', mod)).toBe(false)
    expect(durationAllowed('30d', 'warn', mod)).toBe(true)
    expect(durationAllowed('permanent', 'warn', mod)).toBe(false)
    expect(durationAllowed('permanent', 'account_ban', admin)).toBe(true)
    expect(draftProblem({ ...emptyDraft('account_ban'), reasonCode: 'cheating' }, mod)).toBe('kind')
    expect(draftProblem({ ...emptyDraft('chat_mute'), duration: 'custom', customValue: 8, customUnit: 'days', reasonCode: 'spam' }, mod)).toBe('custom')
    expect(draftProblem({ ...emptyDraft('chat_mute'), duration: 'custom', customValue: 8, customUnit: 'days', reasonCode: 'spam' }, admin)).toBeNull()
    expect(draftProblem(emptyDraft('warn'), admin)).toBe('reasonCode')
    expect(draftProblem({ ...emptyDraft('warn'), reasonCode: 'spam', reason: 'zwei\nZeilen' }, admin)).toBe('reason')
  })

  it('Entwurf → Anfrage', () => {
    const d = { ...emptyDraft('social_ban'), duration: 'custom' as const, customValue: 90, customUnit: 'minutes' as const, reasonCode: 'harassment' as const, reason: ' Belästigung ', note: ' intern ' }
    expect(draftMinutes(d)).toBe(90)
    expect(draftToInput('b0b0', d, 'r0123456789abcdef')).toEqual({
      uuid: 'b0b0',
      kind: 'social_ban',
      duration: 'custom',
      minutes: 90,
      reasonCode: 'harassment',
      reason: 'Belästigung',
      note: 'intern',
      reportId: 'r0123456789abcdef',
    })
    expect(draftMinutes({ duration: 'permanent', customValue: 1, customUnit: 'days' })).toBeNull()
  })

  it('Team-Antworten vertragen fehlende Felder', () => {
    const s = adminSanctionSchema.parse({
      id: 5,
      player: { uuid: 'b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0', name: null },
      kind: 'hosting_ban',
      reasonCode: 'auto_spam',
      createdAt: 'x',
      createdBy: { uuid: 'system', name: null },
      createdRole: 'system',
      status: 'active',
    })
    expect(s.changes).toEqual([])
    expect(s.appeal).toBeNull()
    const file = playerFileSchema.parse({ player: { uuid: 'b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0' } })
    expect(file.reports.against.counts.total).toBe(0)
    expect(file.can.sanction).toBe(false)
    const d = dashboardSchema.parse({ reports: { open: 3 }, sanctions: { chat_mute: 2 } })
    expect(d.reports.open).toBe(3)
    expect(d.series.days).toEqual([])
    expect(d.server).toBeNull()
  })
})
