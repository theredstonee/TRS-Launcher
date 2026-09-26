import { describe, expect, it } from 'vitest'
import { listAudit } from '../server/lib/admin'
import { readEvidenceFile, uploadAttachment } from '../server/lib/attachments'
import { createGroup, deleteMessage, listMessages, openDm, sendMessage } from '../server/lib/chat'
import { one } from '../server/lib/db'
import {
  activeMute,
  adminAddNote,
  adminListReports,
  adminReportAction,
  adminReportDetail,
  adminSetStatus,
  adminUserModeration,
  createReport,
  listMyReports,
  muteUser,
  myModeration,
  sweepModeration,
  unmuteUser,
} from '../server/lib/moderation'
import { deleteUser, isBanned } from '../server/lib/users'
import { befriend, code, listen, players } from './chathelpers'
import { ADMIN, login, makeEnv, solidPng, type TestEnv } from './helpers'

async function groupWith(env: TestEnv, n: number) {
  const names = ['Owner', 'Troll', ...Array.from({ length: n }, (_, i) => `Member${i}`)]
  const users = await players(env, ...names)
  const [owner, troll, ...rest] = users
  for (const u of [troll!, ...rest]) befriend(env, owner!, u)
  const g = createGroup(env.ctx, owner!.uuid, 'Crew', [troll!.uuid, ...rest.map((u) => u.uuid)])
  return { owner: owner!, troll: troll!, rest, g }
}

describe('reports', () => {
  it('reporting a message snapshots 10 before/after, encrypted; access and duplicates are checked', async () => {
    const env = makeEnv()
    const { owner, troll, rest, g } = await groupWith(env, 1)
    const reporter = rest[0]!
    for (let i = 0; i < 12; i++) sendMessage(env.ctx, owner.uuid, g.id, { text: `vorher ${i}` })
    const bad = sendMessage(env.ctx, troll.uuid, g.id, { text: 'du bist so dumm' }).message
    for (let i = 0; i < 3; i++) sendMessage(env.ctx, owner.uuid, g.id, { text: `nachher ${i}` })

    const outsider = (await players(env, 'Outsider'))[0]!
    expect(code(() => createReport(env.ctx, outsider.uuid, { kind: 'message', reason: 'insult_hate', messageId: bad.id }))).toBe('message_not_found')
    expect(code(() => createReport(env.ctx, troll.uuid, { kind: 'message', reason: 'spam', messageId: bad.id }))).toBe('cannot_target_self')

    const evR = listen(env, reporter.uuid)
    const r = createReport(env.ctx, reporter.uuid, { kind: 'message', reason: 'insult_hate', note: 'beleidigt alle', messageId: bad.id })
    expect(r).toMatchObject({ kind: 'message', reason: 'insult_hate', status: 'open', outcome: null })
    expect(code(() => createReport(env.ctx, reporter.uuid, { kind: 'message', reason: 'spam', messageId: bad.id }))).toBe('already_reported')
    expect(listMyReports(env.ctx, reporter.uuid).map((x) => x.id)).toEqual([r.id])

    // Beweis verschlüsselt gespeichert.
    const raw = one<{ evidence: Uint8Array }>(env.ctx.db, 'SELECT evidence FROM chat_reports WHERE id = ?', r.id)!.evidence
    expect(Buffer.from(raw).toString('latin1')).not.toContain('dumm')

    // Löschen der Nachricht ändert den Beweis nicht.
    deleteMessage(env.ctx, troll.uuid, bad.id)
    const d = adminReportDetail(env.ctx, r.id)
    expect(d).toMatchObject({ reporter: { name: 'Member0' }, target: { uuid: troll.uuid, name: 'Troll' }, note: 'beleidigt alle', preview: 'du bist so dumm' })
    const texts = d.evidence!.messages.map((m) => m.text ?? m.system?.event)
    const focus = texts.indexOf('du bist so dumm')
    expect(focus).toBe(10)
    expect(texts.slice(0, 10)).toEqual(['vorher 2', 'vorher 3', 'vorher 4', 'vorher 5', 'vorher 6', 'vorher 7', 'vorher 8', 'vorher 9', 'vorher 10', 'vorher 11'])
    expect(texts.slice(11)).toEqual(['nachher 0', 'nachher 1', 'nachher 2'])
    expect(d.evidence!.conversation).toMatchObject({ kind: 'group', name: 'Crew' })
    expect(evR.of('report_update')).toEqual([])
  })

  it('image reports keep a copy for admins even after deletion', async () => {
    const env = makeEnv()
    const [a, b] = await players(env, 'Alex', 'Bob')
    befriend(env, a!, b!)
    const dm = openDm(env.ctx, a!.uuid, b!.uuid)
    const att = await uploadAttachment(env.ctx, a!.uuid, solidPng(20, 20), 'image/png')
    const m = sendMessage(env.ctx, a!.uuid, dm.id, { attachments: [att.id] }).message
    const r = createReport(env.ctx, b!.uuid, { kind: 'image', reason: 'inappropriate', attachmentId: att.id })
    deleteMessage(env.ctx, a!.uuid, m.id)
    const d = adminReportDetail(env.ctx, r.id)
    expect(d.evidence!.images).toEqual([{ id: att.id, width: 20, height: 20, mime: 'image/jpeg', path: `/v1/admin/reports/${r.id}/images/${att.id}` }])
    expect(readEvidenceFile(env.ctx, r.id, att.id)!.data.subarray(0, 2)).toEqual(Buffer.from([0xff, 0xd8]))
    // Aufbewahrung: nach Erledigung + 90 Tagen sind die Inhalte weg, nach einem Jahr die Meldung.
    adminReportAction(env.ctx, ADMIN, r.id, { action: 'dismiss' })
    env.clock.advance(91 * 86_400_000)
    sweepModeration(env.ctx)
    expect(readEvidenceFile(env.ctx, r.id, att.id)).toBeNull()
    expect(adminReportDetail(env.ctx, r.id)).toMatchObject({ evidence: null, note: null, evidencePurged: true })
    env.clock.advance(365 * 86_400_000)
    sweepModeration(env.ctx)
    expect(code(() => adminReportDetail(env.ctx, r.id))).toBe('report_not_found')
  })

  it('player and group reports need access; group reports target the owner', async () => {
    const env = makeEnv()
    const { owner, troll, rest, g } = await groupWith(env, 1)
    const outsider = (await players(env, 'Outsider'))[0]!
    expect(code(() => createReport(env.ctx, outsider.uuid, { kind: 'group', reason: 'spam', conversationId: g.id }))).toBe('conversation_not_found')
    expect(code(() => createReport(env.ctx, owner.uuid, { kind: 'group', reason: 'spam', conversationId: g.id }))).toBe('cannot_target_self')
    const gr = createReport(env.ctx, rest[0]!.uuid, { kind: 'group', reason: 'scam_phishing', conversationId: g.id })
    expect(adminReportDetail(env.ctx, gr.id).target?.uuid).toBe(owner.uuid)
    expect(code(() => createReport(env.ctx, outsider.uuid, { kind: 'player', reason: 'harassment', uuid: 'f'.repeat(32) }))).toBe('player_not_found')
    expect(code(() => createReport(env.ctx, outsider.uuid, { kind: 'player', reason: 'harassment', uuid: troll.uuid, conversationId: g.id }))).toBe('conversation_not_found')
    const pr = createReport(env.ctx, outsider.uuid, { kind: 'player', reason: 'harassment', uuid: troll.uuid })
    expect(adminReportDetail(env.ctx, pr.id).evidence!.messages).toEqual([])
  })

  it('auto-mutes after 3 distinct trusted reporters until review; dismiss lifts it and informs reporters', async () => {
    const env = makeEnv()
    const { troll, rest, g } = await groupWith(env, 4)
    const bad = sendMessage(env.ctx, troll.uuid, g.id, { text: 'Spam Spam' }).message
    const evT = listen(env, troll.uuid)
    const evR = listen(env, rest[0]!.uuid)
    const reports = rest.slice(0, 3).map((u) => createReport(env.ctx, u.uuid, { kind: 'message', reason: 'spam', messageId: bad.id }))
    expect(activeMute(env.ctx, troll.uuid)).toMatchObject({ auto: 'reports', expires_at: null })
    expect(evT.of('moderation')).toEqual([{ type: 'moderation', action: 'mute', reason: 'Automatic: several reports, pending review', until: null }])
    expect(myModeration(env.ctx, troll.uuid).mute).toMatchObject({ until: null, auto: 'reports' })
    expect(code(() => sendMessage(env.ctx, troll.uuid, g.id, { text: 'hallo?' }))).toBe('chat_muted')

    // Abweisen mit includeRelated → alle drei erledigt, Stumm aufgehoben, Melder informiert.
    const d = adminReportAction(env.ctx, ADMIN, reports[0]!.id, { action: 'dismiss', includeRelated: true })
    expect(d).toMatchObject({ status: 'resolved', outcome: 'dismissed' })
    expect(adminListReports(env.ctx, { status: 'active', limit: 50 }).reports).toEqual([])
    expect(activeMute(env.ctx, troll.uuid)).toBeUndefined()
    expect(evT.of('moderation').at(-1)).toMatchObject({ action: 'unmute' })
    expect(evR.of('report_update')).toEqual([
      { type: 'report_update', report: expect.objectContaining({ id: reports[0]!.id, status: 'resolved', outcome: 'dismissed' }) },
    ])
    expect(sendMessage(env.ctx, troll.uuid, g.id, { text: 'wieder frei' }).created).toBe(true)
  })

  it('reporters with many dismissed reports do not trigger auto-mute; open report cap', async () => {
    const env = makeEnv({ limits: { maxOpenReportsPerUser: 2 } })
    const { owner, troll, rest, g } = await groupWith(env, 3)
    const liar = rest[0]!
    // Drei abgewiesene Meldungen → wenig Vertrauen.
    for (let i = 0; i < 3; i++) {
      const m = sendMessage(env.ctx, owner.uuid, g.id, { text: `harmlos ${i}` }).message
      const r = createReport(env.ctx, liar.uuid, { kind: 'message', reason: 'spam', messageId: m.id })
      adminReportAction(env.ctx, ADMIN, r.id, { action: 'dismiss' })
    }
    expect(adminUserModeration(env.ctx, liar.uuid).reportsFiled).toMatchObject({ dismissed: 3, lowTrust: true })
    const bad = sendMessage(env.ctx, troll.uuid, g.id, { text: 'böse' }).message
    const low = createReport(env.ctx, liar.uuid, { kind: 'message', reason: 'spam', messageId: bad.id })
    createReport(env.ctx, rest[1]!.uuid, { kind: 'message', reason: 'spam', messageId: bad.id })
    createReport(env.ctx, rest[2]!.uuid, { kind: 'message', reason: 'spam', messageId: bad.id })
    expect(activeMute(env.ctx, troll.uuid)).toBeUndefined()
    expect(adminReportDetail(env.ctx, low.id)).toMatchObject({ lowTrust: true, reporterStats: { dismissed: 3, low: true } })
    // Höchstens 2 offene Meldungen je Melder.
    const m2 = sendMessage(env.ctx, troll.uuid, g.id, { text: 'nochmal' }).message
    createReport(env.ctx, liar.uuid, { kind: 'player', reason: 'other', uuid: troll.uuid })
    expect(code(() => createReport(env.ctx, liar.uuid, { kind: 'message', reason: 'spam', messageId: m2.id }))).toBe('too_many_open_reports')
  })
})

describe('admin moderation', () => {
  it('list/filter, status, notes, actions (delete, warn, mute, ban) with audit log', async () => {
    const env = makeEnv()
    await login(env, 'Admin', ADMIN)
    const { owner, troll, rest, g } = await groupWith(env, 1)
    const bad = sendMessage(env.ctx, troll.uuid, g.id, { text: 'kauf gold bei mir' }).message
    const r = createReport(env.ctx, rest[0]!.uuid, { kind: 'message', reason: 'scam_phishing', messageId: bad.id })
    env.clock.advance(1000)
    const r2 = createReport(env.ctx, owner.uuid, { kind: 'player', reason: 'harassment', uuid: troll.uuid })

    const list = adminListReports(env.ctx, { status: 'open', limit: 1 })
    expect(list.reports.map((x) => x.id)).toEqual([r.id])
    expect(list.counts).toEqual({ open: 2, in_review: 0, resolved: 0 })
    expect(list.reports[0]).toMatchObject({ targetOpenReports: 2, preview: 'kauf gold bei mir' })
    const page2 = adminListReports(env.ctx, { status: 'open', limit: 1, cursor: list.nextCursor! })
    expect(page2.reports.map((x) => x.id)).toEqual([r2.id])
    expect(adminListReports(env.ctx, { status: 'all', kind: 'player', limit: 10 }).reports.map((x) => x.id)).toEqual([r2.id])
    expect(adminListReports(env.ctx, { status: 'all', target: rest[0]!.uuid, limit: 10 }).reports).toEqual([])

    const evReporter = listen(env, rest[0]!.uuid)
    const evTroll = listen(env, troll.uuid)
    const evOwner = listen(env, owner.uuid)
    expect(adminSetStatus(env.ctx, ADMIN, r.id, 'in_review')).toMatchObject({ status: 'in_review', assignedTo: { uuid: ADMIN } })
    expect(evReporter.of('report_update')[0]!.report.status).toBe('in_review')
    expect(adminAddNote(env.ctx, ADMIN, r.id, 'Phishing-Versuch, Link folgt').notes[0]).toMatchObject({ actor: ADMIN, actorName: 'Admin', text: 'Phishing-Versuch, Link folgt' })

    adminReportAction(env.ctx, ADMIN, r.id, { action: 'delete_message', keepOpen: true })
    expect(evOwner.of('chat_message_deleted')[0]!.message).toMatchObject({ id: bad.id, deleted: true, deletedBy: 'admin' })
    expect(listMessages(env.ctx, owner.uuid, g.id, { limit: 20 }).messages.find((m) => m.id === bad.id)!.text).toBeNull()
    adminReportAction(env.ctx, ADMIN, r.id, { action: 'warn', reason: 'Kein Handel', keepOpen: true })
    expect(evTroll.of('moderation')).toEqual([{ type: 'moderation', action: 'warn', reason: 'Kein Handel', until: null }])
    const final = adminReportAction(env.ctx, ADMIN, r.id, { action: 'mute', minutes: 60, reason: 'Betrug' })
    expect(final).toMatchObject({ status: 'resolved', outcome: 'actioned' })
    expect(final.targetModeration!.mute).toMatchObject({ kind: 'mute', active: true, reason: 'Betrug' })
    expect(evTroll.of('moderation').at(-1)).toMatchObject({ action: 'mute', until: new Date(env.clock.t + 3_600_000).toISOString() })
    expect(evReporter.of('report_update').at(-1)!.report).toMatchObject({ status: 'resolved', outcome: 'actioned' })
    expect(final.audit.map((a) => a.action)).toEqual(['report.in_review', 'report.note', 'chat.message.delete', 'chat.warn', 'chat.mute', 'report.actioned'])
    expect(listAudit(env.ctx, { ref: r.id, limit: 50 }).entries).toHaveLength(6)
    expect(adminUserModeration(env.ctx, troll.uuid)).toMatchObject({ reportsAgainst: { total: 2, open: 1, actioned: 1 } })

    // Admins lassen sich nicht stummschalten; Sperre über die Meldung.
    expect(code(() => muteUser(env.ctx, ADMIN, ADMIN, 10, null))).toBe('cannot_moderate_admin')
    adminReportAction(env.ctx, ADMIN, r2.id, { action: 'ban', reason: 'Wiederholt' })
    expect(isBanned(env.ctx, troll.uuid)).toBe(true)
    expect(code(() => adminReportAction(env.ctx, ADMIN, r2.id, { action: 'resolve' }))).toBe('ok')
    expect(code(() => adminSetStatus(env.ctx, ADMIN, r2.id, 'open'))).toBe('report_resolved')

    // Manuell aufheben.
    expect(unmuteUser(env.ctx, ADMIN, troll.uuid)).toBe(1)
    expect(activeMute(env.ctx, troll.uuid)).toBeUndefined()
  })

  it('account deletion keeps reports against the account but removes warnings; an active mute survives', async () => {
    const env = makeEnv()
    const { troll, rest, g } = await groupWith(env, 1)
    const bad = sendMessage(env.ctx, troll.uuid, g.id, { text: 'böse' }).message
    const r = createReport(env.ctx, rest[0]!.uuid, { kind: 'message', reason: 'insult_hate', messageId: bad.id })
    adminReportAction(env.ctx, ADMIN, r.id, { action: 'warn', reason: 'x', keepOpen: true })
    muteUser(env.ctx, ADMIN, troll.uuid, 60, 'y')
    deleteUser(env.ctx, troll.uuid)
    expect(adminReportDetail(env.ctx, r.id).evidence!.messages.some((m) => m.text === 'böse')).toBe(true)
    expect(adminUserModeration(env.ctx, troll.uuid).sanctions.map((s) => s.kind)).toEqual(['mute'])
    // Melder löscht sein Konto → Meldung bleibt anonym.
    deleteUser(env.ctx, rest[0]!.uuid)
    expect(adminReportDetail(env.ctx, r.id).reporter).toBeNull()
  })
})
