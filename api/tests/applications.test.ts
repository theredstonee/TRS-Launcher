import { describe, expect, it } from 'vitest'
import {
  APPLICATION_RETENTION_MS,
  addApplicationNote,
  adminApplicationDetail,
  adminListApplications,
  createJob,
  deleteJob,
  eligibility,
  myApplications,
  normalizeDiscord,
  publicJob,
  publicJobs,
  setApplicationStatus,
  submitApplication,
  sweepApplications,
  updateJob,
  validateAnswers,
  voteApplication,
  removeVote,
  withdrawApplication,
  type FormField,
  type JobInput,
} from '../server/lib/applications'
import { all, one } from '../server/lib/db'
import { createSanction } from '../server/lib/sanctions'
import { ownerStaff, setMemberRoles, teamOf, updateRole, type Staff } from '../server/lib/team'
import { deleteUser } from '../server/lib/users'
import { code, listen, players } from './chathelpers'
import { ADMIN, login, makeEnv, type TestEnv } from './helpers'

const OWNER = ownerStaff(ADMIN)
const DAY_MS = 86_400_000

const FORM: FormField[] = [
  { id: 'why', type: 'long', required: true, label: { en: 'Why do you want to join?', de: 'Warum willst du ins Team?' }, min: 20, max: 500 },
  { id: 'hours', type: 'number', required: true, label: { en: 'Hours per week' }, min: 1, max: 60 },
  { id: 'exp', type: 'yesno', required: false, label: { en: 'Moderated before?' } },
  { id: 'lang', type: 'multi', required: true, label: { en: 'Languages' }, min: 1, max: 2, options: [{ id: 'de', label: { en: 'German' } }, { id: 'en', label: { en: 'English' } }, { id: 'es', label: { en: 'Spanish' } }] },
  { id: 'tz', type: 'single', required: false, label: { en: 'Time zone' }, options: [{ id: 'eu', label: { en: 'Europe' } }, { id: 'us', label: { en: 'Americas' } }] },
  { id: 'nick', type: 'short', required: false, label: { en: 'Nickname' }, max: 20 },
]

const JOB: JobInput = {
  status: 'open',
  roleId: 'moderator',
  texts: {
    en: { title: 'Moderator', summary: 'Keep chat friendly', description: 'You help.', tasks: ['Handle reports'], requirements: ['16+'] },
    de: { title: 'Moderator (m/w/d)', summary: 'Chat freundlich halten', description: 'Du hilfst.', tasks: ['Meldungen bearbeiten'], requirements: ['16+'] },
  },
  form: FORM,
}

const GOOD = { discord: 'Steve.Builds', ageGroup: '16-17', lang: 'de', answers: { why: 'Ich helfe gern anderen Spielern im Chat.', hours: 5, exp: true, lang: ['de', 'en'], tz: 'eu' } }

async function setup(env: TestEnv) {
  await login(env, 'Owner', ADMIN)
  const [admin, recruiter, recruiter2, senior, steve, alex] = await players(env, 'Admin', 'Recruiter', 'Recruiter2', 'Senior', 'Steve', 'Alex')
  setMemberRoles(env.ctx, OWNER, admin!.uuid, ['admin'])
  setMemberRoles(env.ctx, OWNER, recruiter!.uuid, ['recruiter'])
  setMemberRoles(env.ctx, OWNER, recruiter2!.uuid, ['recruiter'])
  setMemberRoles(env.ctx, OWNER, senior!.uuid, ['senior_moderator'])
  const st = (u: { uuid: string }) => teamOf(env.ctx, u.uuid)!
  const job = createJob(env.ctx, st(admin!), JOB)
  return {
    admin: st(admin!), recruiter: st(recruiter!), recruiter2: st(recruiter2!), senior: st(senior!),
    steve: { uuid: steve!.uuid, name: steve!.name }, alex: { uuid: alex!.uuid, name: alex!.name }, job,
  }
}

describe('jobs', () => {
  it('only applications.manage creates jobs; slugs, drafts and closed jobs', async () => {
    const env = makeEnv()
    const t = await setup(env)
    expect(t.job.id).toBe('moderator')
    expect(code(() => createJob(env.ctx, t.recruiter, JOB))).toBe('missing_permission')
    const again = createJob(env.ctx, t.admin, { ...JOB, status: 'draft' })
    expect(again.id).toMatch(/^moderator-[0-9a-f]{4}$/)
    expect(publicJobs(env.ctx).map((j) => j.id)).toEqual(['moderator'])
    expect(code(() => publicJob(env.ctx, again.id))).toBe('job_not_found')
    updateJob(env.ctx, t.admin, again.id, { ...JOB, status: 'closed' })
    expect(publicJobs(env.ctx, true).map((j) => [j.id, j.status])).toEqual([['moderator', 'open'], [again.id, 'closed']])
    expect(code(() => deleteJob(env.ctx, t.admin, again.id))).toBe('owner_only')
    deleteJob(env.ctx, OWNER, again.id)
    // Ungültige Formulare und Texte.
    expect(code(() => createJob(env.ctx, t.admin, { ...JOB, texts: {} }))).toBe('invalid_request')
    expect(code(() => createJob(env.ctx, t.admin, { ...JOB, form: [{ id: 'discord', type: 'short', label: { en: 'x' } }] }))).toBe('invalid_request')
    expect(code(() => createJob(env.ctx, t.admin, { ...JOB, form: [{ id: 'pick', type: 'single', label: { en: 'x' }, options: [{ id: 'a', label: { en: 'A' } }] }] }))).toBe('invalid_request')
    expect(code(() => createJob(env.ctx, t.admin, { ...JOB, form: [FORM[0]!, FORM[0]!] }))).toBe('invalid_request')
    expect(code(() => createJob(env.ctx, t.admin, { ...JOB, form: [{ id: 'long', type: 'long', label: { en: 'x' }, max: 9000 }] }))).toBe('invalid_request')
    expect(code(() => createJob(env.ctx, t.admin, { ...JOB, roleId: 'owner' }))).toBe('invalid_role')
    expect(code(() => createJob(env.ctx, t.admin, { ...JOB, texts: { en: { title: '<b>x</b>'.repeat(30) } } }))).toBe('invalid_request')
  })
})

describe('deleting positions', () => {
  it('only owners delete; applications only go with an explicit confirmation', async () => {
    const env = makeEnv()
    const t = await setup(env)
    const app = submitApplication(env.ctx, t.steve, 'moderator', GOOD)
    expect(code(() => deleteJob(env.ctx, t.admin, 'moderator', true))).toBe('owner_only')
    expect(code(() => deleteJob(env.ctx, OWNER, 'moderator'))).toBe('job_has_applications')
    expect(one(env.ctx.db, 'SELECT 1 AS x FROM team_applications WHERE id = ?', app.id)).toBeTruthy()
    expect(deleteJob(env.ctx, OWNER, 'moderator', true)).toEqual({ applications: 1 })
    expect(one(env.ctx.db, 'SELECT 1 AS x FROM team_applications WHERE id = ?', app.id)).toBeUndefined()
    expect(one(env.ctx.db, "SELECT 1 AS x FROM team_jobs WHERE id = 'moderator'")).toBeUndefined()
    expect(all<{ action: string }>(env.ctx.db, "SELECT action FROM admin_log WHERE action = 'job.delete'")).toHaveLength(1)
    expect(code(() => deleteJob(env.ctx, OWNER, 'moderator', true))).toBe('job_not_found')
  })
})

describe('applying', () => {
  it('validates the form: standard fields, required, limits, options, unknown fields, honeypot', async () => {
    const env = makeEnv()
    const t = await setup(env)
    expect(normalizeDiscord('@Steve.Builds')).toBe('steve.builds')
    expect(normalizeDiscord('Old Name#1234')).toBe('Old Name#1234')
    for (const bad of ['a', 'two..dots', 'with space', 'x'.repeat(33), 'emoji😀']) expect(normalizeDiscord(bad)).toBeNull()
    const v = validateAnswers(FORM, { why: 'kurz', hours: 0, lang: ['de', 'en', 'es'], tz: 'mars', nick: 'x'.repeat(21), extra: 1 })
    expect(v.errors).toEqual([
      { id: 'extra', error: 'invalid' },
      { id: 'why', error: 'too_short' },
      { id: 'hours', error: 'too_small' },
      { id: 'lang', error: 'too_many' },
      { id: 'tz', error: 'invalid' },
      { id: 'nick', error: 'too_long' },
    ])
    expect(validateAnswers(FORM, {}).errors.map((e) => e.id)).toEqual(['why', 'hours', 'lang'])
    // Text wird gesäubert (Steuerzeichen raus), Kurztext ohne Zeilenumbrüche.
    expect(validateAnswers(FORM, { ...GOOD.answers, nick: 'Ste\u0007ve\nB' }).answers.nick).toBe('Steve B')

    const err = (() => {
      try {
        submitApplication(env.ctx, t.steve, 'moderator', { ...GOOD, discord: 'no..pe', answers: {} })
      } catch (e) {
        return e as { code: string, details: { fields: { id: string }[] } }
      }
    })()!
    expect(err.code).toBe('invalid_answers')
    expect(err.details.fields.map((f) => f.id)).toEqual(['discord', 'why', 'hours', 'lang'])
    expect(code(() => submitApplication(env.ctx, t.steve, 'moderator', { ...GOOD, ageGroup: '21' }))).toBe('invalid_request')
    expect(code(() => submitApplication(env.ctx, t.steve, 'moderator', { ...GOOD, birthday: '2010-01-01' }))).toBe('invalid_request')
    expect(code(() => submitApplication(env.ctx, t.steve, 'moderator', { ...GOOD, website: 'http://spam' }))).toBe('invalid_request')
    expect(all(env.ctx.db, 'SELECT * FROM team_applications')).toHaveLength(0)
  })

  it('one open application per job, cooldown after a rejection, 24 h after withdrawing, no role twice', async () => {
    const env = makeEnv()
    const t = await setup(env)
    const ev = listen(env, t.steve.uuid)
    const a = submitApplication(env.ctx, t.steve, 'moderator', GOOD)
    expect(a).toMatchObject({ status: 'new', job: { id: 'moderator', title: { en: 'Moderator', de: 'Moderator (m/w/d)' } }, canWithdraw: true })
    expect(ev.of('application_updated')).toHaveLength(1)
    expect(eligibility(env.ctx, t.steve.uuid, 'moderator')).toMatchObject({ canApply: false, reason: 'open_application', applicationId: a.id })
    expect(code(() => submitApplication(env.ctx, t.steve, 'moderator', GOOD))).toBe('application_open')

    // Ablehnen mit Antwort → Wartezeit (Standard 30 Tage).
    setApplicationStatus(env.ctx, t.admin, a.id, { status: 'rejected', response: 'Leider nicht diesmal.' })
    expect(ev.of('application_updated').at(-1)?.application).toMatchObject({ status: 'rejected', response: 'Leider nicht diesmal.', canWithdraw: false })
    const el = eligibility(env.ctx, t.steve.uuid, 'moderator')
    expect(el).toMatchObject({ canApply: false, reason: 'cooldown' })
    expect(Date.parse(el.retryAt!)).toBe(env.clock.t + 30 * DAY_MS)
    env.clock.advance(30 * DAY_MS + 1)
    expect(eligibility(env.ctx, t.steve.uuid, 'moderator').canApply).toBe(true)

    // Eigene Wartezeit je Stelle; Zurückziehen → 24 h.
    updateJob(env.ctx, t.admin, 'moderator', { ...JOB, cooldownDays: 3 })
    const b = submitApplication(env.ctx, t.steve, 'moderator', GOOD)
    withdrawApplication(env.ctx, t.steve.uuid, b.id)
    expect(code(() => withdrawApplication(env.ctx, t.steve.uuid, b.id))).toBe('application_closed')
    expect(code(() => withdrawApplication(env.ctx, t.alex.uuid, b.id))).toBe('application_not_found')
    expect(eligibility(env.ctx, t.steve.uuid, 'moderator').reason).toBe('cooldown')
    env.clock.advance(DAY_MS + 1)
    const c = submitApplication(env.ctx, t.steve, 'moderator', GOOD)
    // Angenommen mit Rolle → erneut bewerben nicht nötig („member“).
    setApplicationStatus(env.ctx, t.admin, c.id, { status: 'accepted', grantRole: true, response: 'Willkommen!' })
    expect(teamOf(env.ctx, t.steve.uuid)?.roles).toEqual(['moderator'])
    expect(eligibility(env.ctx, t.steve.uuid, 'moderator').reason).toBe('member')
    expect(myApplications(env.ctx, t.steve.uuid).map((x) => x.status)).toEqual(['accepted', 'withdrawn', 'rejected'])

    // Geschlossen → keine neuen Bewerbungen.
    updateJob(env.ctx, t.admin, 'moderator', { ...JOB, status: 'closed' })
    expect(code(() => submitApplication(env.ctx, t.alex, 'moderator', GOOD))).toBe('job_closed')
  })

  it('at most 3 open applications per account', async () => {
    const env = makeEnv()
    const t = await setup(env)
    const ids = ['a1', 'a2', 'a3', 'a4'].map((n) => createJob(env.ctx, t.admin, { ...JOB, id: `job-${n}`, roleId: null, form: [] }).id)
    for (const id of ids.slice(0, 3)) submitApplication(env.ctx, t.alex, id, { discord: 'alex', ageGroup: '18+' })
    expect(code(() => submitApplication(env.ctx, t.alex, ids[3]!, { discord: 'alex', ageGroup: '18+' }))).toBe('too_many_open')
  })
})

describe('reviewing', () => {
  it('lists, votes, notes and history; nobody sees or votes on their own application', async () => {
    const env = makeEnv()
    const t = await setup(env)
    const a = submitApplication(env.ctx, t.steve, 'moderator', GOOD)
    const list = adminListApplications(env.ctx, t.recruiter, { limit: 50 })
    expect(list.applications).toMatchObject([{ id: a.id, applicant: { name: 'Steve' }, ageGroup: '16-17', status: 'new', votes: { up: 0, down: 0, mine: null } }])
    expect(list.counts.new).toBe(1)

    voteApplication(env.ctx, t.recruiter, a.id, { vote: 1, comment: 'Wirkt engagiert' })
    voteApplication(env.ctx, t.recruiter2, a.id, { vote: -1, comment: 'Zu wenig Zeit' })
    voteApplication(env.ctx, t.recruiter2, a.id, { vote: 1 })
    let d = adminApplicationDetail(env.ctx, t.recruiter, a.id)
    expect(d.votes).toEqual({ up: 2, down: 0, mine: 1 })
    expect(d.voteList.map((v) => [v.name, v.vote, v.comment])).toEqual([['Recruiter', 1, 'Wirkt engagiert'], ['Recruiter2', 1, null]])
    removeVote(env.ctx, t.recruiter2, a.id)
    addApplicationNote(env.ctx, t.recruiter, a.id, { text: 'Discord-Gespräch am Freitag' })
    setApplicationStatus(env.ctx, t.recruiter, a.id, { status: 'interview' })
    d = adminApplicationDetail(env.ctx, t.recruiter, a.id)
    expect(d).toMatchObject({ status: 'interview', discord: 'steve.builds', lang: 'de', notes: 1, votes: { up: 1, down: 0 } })
    expect(d.answers).toEqual(GOOD.answers)
    expect(d.form.map((f) => f.id)).toEqual(FORM.map((f) => f.id))
    expect(d.history.map((h) => [h.action, h.detail])).toEqual([
      ['submitted', null], ['vote', 'up'], ['vote', 'down'], ['vote', 'up'], ['vote', 'removed'], ['status', 'new → interview'],
    ])
    // Rechte: Bewerbungs-Team entscheidet nicht; ohne players.view kein Strafverlauf.
    expect(d.can).toEqual({ review: true, decide: false, grantRole: false, vote: true })
    expect(d.player).toBeNull()
    expect(code(() => setApplicationStatus(env.ctx, t.recruiter, a.id, { status: 'accepted' }))).toBe('missing_permission')
    expect(code(() => addApplicationNote(env.ctx, t.recruiter, a.id, { text: '' }))).toBe('invalid_request')
    expect(code(() => voteApplication(env.ctx, t.recruiter, a.id, { vote: 2 }))).toBe('invalid_request')
    // Senior sieht den Strafverlauf (players.view).
    createSanction(env.ctx, OWNER, { uuid: t.steve.uuid, kind: 'warn', minutes: 60, reasonCode: 'spam' })
    expect(adminApplicationDetail(env.ctx, t.senior, a.id).player).toMatchObject({ rank: 0, active: 1 })

    // Eigene Bewerbung (Team-Mitglied bewirbt sich) sieht nur das restliche Team.
    const own = submitApplication(env.ctx, { uuid: t.recruiter.uuid, name: 'Recruiter' }, 'moderator', GOOD)
    expect(code(() => adminApplicationDetail(env.ctx, t.recruiter, own.id))).toBe('own_application')
    expect(code(() => voteApplication(env.ctx, t.recruiter, own.id, { vote: 1 }))).toBe('own_application')
    expect(adminListApplications(env.ctx, t.recruiter, { limit: 50 }).applications.map((x) => x.id)).toEqual([a.id])
    expect(adminListApplications(env.ctx, t.admin, { limit: 50, q: 'recr' }).applications.map((x) => x.id)).toEqual([own.id])

    // Nach der Entscheidung keine Stimmen mehr.
    setApplicationStatus(env.ctx, t.admin, a.id, { status: 'rejected' })
    expect(code(() => voteApplication(env.ctx, t.recruiter, a.id, { vote: 1 }))).toBe('application_closed')
    expect(adminListApplications(env.ctx, t.admin, { limit: 50, status: 'rejected' }).applications.map((x) => x.id)).toEqual([a.id])
  })

  it('accepting grants the linked role only within the rank rule', async () => {
    const env = makeEnv()
    const t = await setup(env)
    updateRole(env.ctx, OWNER, 'senior_moderator', { permissions: ['applications.view', 'applications.review', 'applications.decide'] })
    const senior: Staff = teamOf(env.ctx, t.senior.uuid)!
    const adminJob = createJob(env.ctx, t.admin, { ...JOB, id: 'admin-job', roleId: 'admin' })
    const a = submitApplication(env.ctx, t.steve, adminJob.id, GOOD)
    expect(code(() => setApplicationStatus(env.ctx, senior, a.id, { status: 'accepted', grantRole: true }))).toBe('rank_too_low')
    expect(code(() => setApplicationStatus(env.ctx, senior, a.id, { status: 'review', grantRole: true }))).toBe('invalid_request')
    const b = submitApplication(env.ctx, t.alex, 'moderator', GOOD)
    const d = setApplicationStatus(env.ctx, senior, b.id, { status: 'accepted', grantRole: true, response: 'Willkommen im Team!' })
    expect(d).toMatchObject({ status: 'accepted', roleGranted: 'moderator', decidedBy: { name: 'Senior' } })
    expect(teamOf(env.ctx, t.alex.uuid)?.roles).toEqual(['moderator'])
    expect(all<{ action: string }>(env.ctx.db, "SELECT action FROM admin_log WHERE ref = ?", `app:${b.id}`).map((r) => r.action)).toEqual(['role.grant.application', 'application.accepted'])
    // Wiederöffnen braucht applications.decide.
    expect(code(() => setApplicationStatus(env.ctx, t.recruiter, b.id, { status: 'review' }))).toBe('missing_permission')
    // Zurückgezogene Bewerbungen bleiben zu.
    const c = submitApplication(env.ctx, t.steve, 'moderator', GOOD)
    withdrawApplication(env.ctx, t.steve.uuid, c.id)
    expect(code(() => setApplicationStatus(env.ctx, t.admin, c.id, { status: 'review' }))).toBe('application_closed')
  })
})

describe('privacy', () => {
  it('deleting the account deletes applications (and their votes/notes)', async () => {
    const env = makeEnv()
    const t = await setup(env)
    const a = submitApplication(env.ctx, t.steve, 'moderator', GOOD)
    voteApplication(env.ctx, t.recruiter, a.id, { vote: 1 })
    addApplicationNote(env.ctx, t.recruiter, a.id, { text: 'Notiz' })
    deleteUser(env.ctx, t.steve.uuid)
    for (const table of ['team_applications', 'team_application_votes', 'team_application_notes', 'team_application_history']) {
      expect(all(env.ctx.db, `SELECT * FROM ${table}`)).toHaveLength(0)
    }
  })

  it('retention: rejected/withdrawn after 6 months; accepted while in the team plus 6 months', async () => {
    const env = makeEnv()
    const t = await setup(env)
    const rejected = submitApplication(env.ctx, t.steve, 'moderator', GOOD)
    setApplicationStatus(env.ctx, t.admin, rejected.id, { status: 'rejected' })
    const accepted = submitApplication(env.ctx, t.alex, 'moderator', GOOD)
    setApplicationStatus(env.ctx, t.admin, accepted.id, { status: 'accepted', grantRole: true })
    env.clock.advance(APPLICATION_RETENTION_MS - DAY_MS)
    sweepApplications(env.ctx)
    expect(all(env.ctx.db, 'SELECT id FROM team_applications')).toHaveLength(2)
    env.clock.advance(2 * DAY_MS)
    sweepApplications(env.ctx)
    expect(all<{ id: string }>(env.ctx.db, 'SELECT id FROM team_applications').map((r) => r.id)).toEqual([accepted.id])
    // Alex bleibt ein Jahr im Team → Bewerbung bleibt; nach dem Austritt noch 6 Monate.
    env.clock.advance(365 * DAY_MS)
    sweepApplications(env.ctx)
    expect(one(env.ctx.db, 'SELECT retain_until FROM team_applications')).toEqual({ retain_until: null })
    setMemberRoles(env.ctx, OWNER, t.alex.uuid, [])
    sweepApplications(env.ctx)
    env.clock.advance(APPLICATION_RETENTION_MS - DAY_MS)
    sweepApplications(env.ctx)
    expect(all(env.ctx.db, 'SELECT id FROM team_applications')).toHaveLength(1)
    env.clock.advance(2 * DAY_MS)
    sweepApplications(env.ctx)
    expect(all(env.ctx.db, 'SELECT id FROM team_applications')).toHaveLength(0)
  })
})
