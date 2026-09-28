import { existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { DatabaseSync } from 'node:sqlite'
import { describe, expect, it } from 'vitest'
import { migrate, one } from '../server/lib/db'
import { formatIssueQuery, parseIssueQuery } from '../shared/issue-query'
import { createIssueBody } from '../server/lib/issue-http'
import {
  addComment,
  addNote,
  adminDeleteComment,
  adminDeleteIssue,
  adminRestoreIssue,
  adminUpdateIssue,
  createIssue,
  deleteOwnComment,
  editComment,
  editIssue,
  issuePage,
  listIssues,
  mergeFilter,
  mergeIssue,
  myIssues,
  readUpload,
  roadmap,
  roadmapColumn,
  scrubLog,
  setFollow,
  sweepIssues,
  uploadIssueImage,
  voteIssue,
  type Viewer,
} from '../server/lib/issues'
import { migrateIssues } from '../server/lib/migrations'
import { adminReportAction, adminReportDetail, createReport } from '../server/lib/moderation'
import { createSanction } from '../server/lib/sanctions'
import { ownerStaff, setMemberRoles, teamOf, updateRole, type Staff } from '../server/lib/team'
import { deleteUser, type UserRow } from '../server/lib/users'
import { code, codeAsync, listen, players } from './chathelpers'
import { ADMIN, makeEnv, solidPng, type TestEnv } from './helpers'

const DAY = 24 * 60 * 60 * 1000
const OWNER = ownerStaff(ADMIN)
const q = (over: Partial<Parameters<typeof listIssues>[1]> = {}) => ({ sort: 'top' as const, page: 1, per: 20, ...over })

function viewer(env: TestEnv, u: UserRow): Viewer {
  return { uuid: u.uuid, name: u.name, staff: teamOf(env.ctx, u.uuid) }
}

function staffOf(env: TestEnv, u: UserRow): Staff {
  return teamOf(env.ctx, u.uuid)!
}

async function setup() {
  const env = makeEnv()
  const [alex, bea, carl, sup, mod] = await players(env, 'Alex', 'Bea', 'Carl', 'Sup', 'Moddy')
  setMemberRoles(env.ctx, OWNER, sup!.uuid, ['supporter'])
  setMemberRoles(env.ctx, OWNER, mod!.uuid, ['moderator'])
  return { env, alex: alex!, bea: bea!, carl: carl!, sup: sup!, mod: mod! }
}

function newIssue(env: TestEnv, u: UserRow, over: Partial<Parameters<typeof createIssue>[2]> = {}) {
  return createIssue(env.ctx, u, { type: 'bug', area: 'launcher', title: 'The launcher crashes on start', description: 'It **crashes** every time.', ...over })
}

describe('issues: create, read, edit', () => {
  it('creates an issue, the author follows it, the page shows it', async () => {
    const { env, alex, bea } = await setup()
    const i = newIssue(env, alex)
    expect(i.number).toBe(1)
    expect(i.url).toMatch(/\/issues\/1$/)
    expect(i.status).toBe('open')
    expect(i.source).toBe('web')
    expect(i.following).toBe(true)
    expect(i.can.edit).toBe(true)
    const page = issuePage(env.ctx, 1, viewer(env, bea))
    expect(page.issue.description).toBe('It **crashes** every time.')
    expect(page.issue.author).toEqual({ uuid: alex.uuid, name: 'Alex', skin: null })
    expect(page.issue.can.edit).toBe(false)
    expect(page.issue.following).toBe(false)
    expect(issuePage(env.ctx, 1, null).issue.following).toBeUndefined()
    expect(code(() => issuePage(env.ctx, 99, null))).toBe('issue_not_found')
  })

  it('cleans titles, checks lengths and blocks filtered words', async () => {
    const { env, alex } = await setup()
    expect(newIssue(env, alex, { title: '  Crash\u202e   on\nstart ' }).title).toBe('Crash on start')
    expect(code(() => newIssue(env, alex, { title: 'abc' }))).toBe('invalid_request')
    expect(code(() => newIssue(env, alex, { description: 'x'.repeat(8001) }))).toBe('invalid_request')
    expect(newIssue(env, alex, { description: '' }).description).toBe('')
  })

  it('keeps technical info from the client: versions + mods public, the log only for author and team', async () => {
    const { env, alex, bea, sup } = await setup()
    const i = newIssue(env, alex, {
      area: 'client',
      meta: {
        modVersion: '0.13.0',
        mcVersion: '1.21.11',
        loader: 'fabric',
        mods: ['fabric-api 0.110.0', 'sodium 0.6.0', 'sodium 0.6.0'],
        log: '[12:00:01] Setting user: Alex\n--accessToken eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NSJ9.abcdefgh --uuid 0123456789abcdef0123456789abcdef\nConnecting to 192.168.1.20, port 25565 [2001:db8::1]\nmail alex@example.com at C:\\Users\\Alex\\AppData',
      },
    })
    expect(i.source).toBe('client')
    expect(i.meta?.mods).toEqual(['fabric-api 0.110.0', 'sodium 0.6.0'])
    expect(i.meta?.hasLog).toBe(true)
    const log = i.meta!.log!
    expect(log).toContain('[12:00:01] Setting user: <player>')
    expect(log).not.toContain('eyJ')
    expect(log).not.toContain('0123456789abcdef0123456789abcdef')
    expect(log).toContain('<ip>')
    expect(log).not.toContain('192.168')
    expect(log).not.toContain('2001:db8')
    expect(log).toContain('<email>')
    expect(log).toContain('C:\\Users\\<user>\\AppData')
    expect(issuePage(env.ctx, i.number, viewer(env, bea)).issue.meta?.log).toBeUndefined()
    expect(issuePage(env.ctx, i.number, null).issue.meta?.mcVersion).toBe('1.21.11')
    expect(issuePage(env.ctx, i.number, viewer(env, sup)).issue.meta?.log).toBe(log)
  })

  it('scrubs tokens, sessions and addresses but keeps times and versions', () => {
    const out = scrubLog('token:abcdefghijkl:0123456789abcdef0123456789abcdef Bearer abc.def.ghi-123 trs_abcdefghijklmnop 12:34:56 1.21.11 v0.16.9', ['Steve'])
    expect(out).toBe('<session> Bearer <redacted> <token> 12:34:56 1.21.11 v0.16.9')
    expect(scrubLog('password=hunter2 "accessToken": "x.y.z"')).toBe('password=<redacted> "accessToken": <redacted>')
  })

  it('lets the author edit only while the issue is open', async () => {
    const { env, alex, bea, sup } = await setup()
    const i = newIssue(env, alex)
    expect(editIssue(env.ctx, viewer(env, alex), i.number, { title: 'Crashes on every start', type: 'feature' }).title).toBe('Crashes on every start')
    expect(code(() => editIssue(env.ctx, viewer(env, bea), i.number, { title: 'Hijacked title' }))).toBe('forbidden')
    adminUpdateIssue(env.ctx, staffOf(env, sup), i.number, { status: 'planned' })
    expect(code(() => editIssue(env.ctx, viewer(env, alex), i.number, { title: 'Too late now' }))).toBe('issue_not_editable')
  })

  it('validates the client request exactly as documented', () => {
    const ok = createIssueBody.safeParse({ type: 'bug', area: 'client', title: 'Crash', description: 'x', attachments: ['A'.repeat(22)], meta: { modVersion: '0.13.0', mods: ['a'], log: 'l' } })
    expect(ok.success).toBe(true)
    expect(createIssueBody.safeParse({ type: 'bug', area: 'client', title: 'Crash', description: 'x', meta: { mods: Array(301).fill('m') } }).success).toBe(false)
    expect(createIssueBody.safeParse({ type: 'bug', area: 'client', title: 'Crash', description: 'x', attachments: Array(7).fill('A'.repeat(22)) }).success).toBe(false)
    expect(createIssueBody.safeParse({ type: 'bug', area: 'client', title: 'Crash', description: 'x', meta: { log: 'x'.repeat(20_001) } }).success).toBe(false)
    expect(createIssueBody.safeParse({ type: 'idea', area: 'client', title: 'Crash', description: 'x' }).success).toBe(false)
  })
})

describe('issues: votes and lists', () => {
  it('counts up and down votes per account, changeable, and blocks closed issues', async () => {
    const { env, alex, bea, carl, sup } = await setup()
    const i = newIssue(env, alex)
    expect(voteIssue(env.ctx, viewer(env, alex), i.number, 1)).toEqual({ score: 1, up: 1, down: 0, myVote: 1 })
    voteIssue(env.ctx, viewer(env, bea), i.number, 1)
    expect(voteIssue(env.ctx, viewer(env, carl), i.number, -1)).toEqual({ score: 1, up: 2, down: 1, myVote: -1 })
    expect(voteIssue(env.ctx, viewer(env, carl), i.number, 1).score).toBe(3)
    expect(voteIssue(env.ctx, viewer(env, carl), i.number, 0)).toEqual({ score: 2, up: 2, down: 0, myVote: 0 })
    const list = listIssues(env.ctx, q(), viewer(env, bea))
    expect(list.issues[0]!.myVote).toBe(1)
    expect(listIssues(env.ctx, q(), null).issues[0]!.myVote).toBeUndefined()
    adminUpdateIssue(env.ctx, staffOf(env, sup), i.number, { status: 'rejected' })
    expect(code(() => voteIssue(env.ctx, viewer(env, carl), i.number, 1))).toBe('issue_closed')
  })

  it('sorts, filters, searches, hides closed issues and pages', async () => {
    const { env, alex, bea, sup } = await setup()
    const a = newIssue(env, alex, { title: 'Alpha crash in launcher', type: 'bug', area: 'launcher' })
    env.clock.advance(1000)
    const b = newIssue(env, alex, { title: 'Beta idea for the client', type: 'feature', area: 'client', description: 'Needle in the description' })
    env.clock.advance(1000)
    const c = newIssue(env, bea, { title: 'Gamma website typo', area: 'website' })
    voteIssue(env.ctx, viewer(env, bea), b.number, 1)
    voteIssue(env.ctx, viewer(env, alex), c.number, -1)
    const nums = (r: ReturnType<typeof listIssues>) => r.issues.map((x) => x.number)
    expect(nums(listIssues(env.ctx, q(), null))).toEqual([b.number, a.number, c.number])
    expect(nums(listIssues(env.ctx, q({ sort: 'new' }), null))).toEqual([c.number, b.number, a.number])
    env.clock.advance(1000)
    addComment(env.ctx, viewer(env, bea), a.number, { body: 'Same here' })
    expect(nums(listIssues(env.ctx, q({ sort: 'activity' }), null))[0]).toBe(a.number)
    expect(nums(listIssues(env.ctx, q({ type: 'feature' }), null))).toEqual([b.number])
    expect(nums(listIssues(env.ctx, q({ area: 'website' }), null))).toEqual([c.number])
    expect(nums(listIssues(env.ctx, q({ q: 'needle' }), null))).toEqual([b.number])
    expect(nums(listIssues(env.ctx, q({ q: `#${c.number}` }), null))).toEqual([c.number])
    expect(nums(listIssues(env.ctx, q({ q: '100%_' }), null))).toEqual([])
    adminUpdateIssue(env.ctx, staffOf(env, sup), a.number, { status: 'done', fixedIn: '0.13.0' })
    expect(nums(listIssues(env.ctx, q(), null))).not.toContain(a.number)
    expect(nums(listIssues(env.ctx, q({ closed: true }), null))).toContain(a.number)
    expect(nums(listIssues(env.ctx, q({ status: ['done'] }), null))).toEqual([a.number])
    const p = listIssues(env.ctx, q({ per: 1, page: 2, closed: true }), null)
    expect(p).toMatchObject({ total: 3, pages: 3, page: 2, per: 1 })
    expect(listIssues(env.ctx, q({ per: 1, page: 99 }), null).page).toBe(2)
  })

  it('builds the six-column roadmap with counts, filters and "load more"', async () => {
    const { env, alex, bea, sup } = await setup()
    const s = staffOf(env, sup)
    const o = newIssue(env, bea, { title: 'Still open idea', type: 'feature', area: 'website' })
    const p1 = newIssue(env, alex, { title: 'Planned low priority' })
    const p2 = newIssue(env, alex, { title: 'Planned critical one', area: 'client' })
    const w = newIssue(env, alex, { title: 'Being worked on' })
    const r = newIssue(env, alex, { title: 'In review now' })
    const d = newIssue(env, alex, { title: 'Done long ago' })
    const x = newIssue(env, alex, { title: 'Rejected wish', type: 'feature' })
    adminUpdateIssue(env.ctx, s, p1.number, { status: 'planned', priority: 'low', tags: ['ui'] })
    adminUpdateIssue(env.ctx, s, p2.number, { status: 'planned', priority: 'critical', assignee: sup.uuid })
    adminUpdateIssue(env.ctx, s, w.number, { status: 'in_progress' })
    adminUpdateIssue(env.ctx, s, r.number, { status: 'in_review' })
    adminUpdateIssue(env.ctx, s, d.number, { status: 'done' })
    adminUpdateIssue(env.ctx, s, x.number, { status: 'rejected' })
    const cols = (f = '') => Object.fromEntries(roadmap(env.ctx, mergeFilter({ filter: f }), viewer(env, sup)).columns.map((c) => [c.status, c.issues.map((i) => i.number)]))
    expect(cols()).toEqual({ open: [o.number], planned: [p2.number, p1.number], in_progress: [w.number], in_review: [r.number], done: [d.number], rejected: [x.number] })
    expect(roadmap(env.ctx, mergeFilter({}), null).columns.map((c) => c.total)).toEqual([1, 2, 1, 1, 1, 1])
    // Suchsyntax (deutsch + englisch gemischt).
    expect(cols('typ:feature')).toMatchObject({ open: [o.number], planned: [], rejected: [x.number] })
    expect(cols('bereich:client prio:kritisch')).toMatchObject({ planned: [p2.number], open: [] })
    expect(cols('assignee:me')).toMatchObject({ planned: [p2.number], in_progress: [] })
    expect(cols('zuständig:niemand status:geplant')).toMatchObject({ planned: [p1.number], open: [] })
    expect(cols('tag:ui')).toMatchObject({ planned: [p1.number] })
    expect(cols('author:BEA')).toMatchObject({ open: [o.number], planned: [] })
    expect(cols('author:nobodyhere').open).toEqual([])
    expect(cols('ist:offen').done).toEqual([])
    expect(cols('is:closed').planned).toEqual([])
    expect(cols('"worked on"').in_progress).toEqual([w.number])
    // Mehr laden: eine Spalte weiterblättern.
    const first = roadmapColumn(env.ctx, 'planned', mergeFilter({}), null, 0, 1)
    const more = roadmapColumn(env.ctx, 'planned', mergeFilter({}), null, 1, 1)
    expect([first.issues[0]!.number, first.hasMore, more.issues[0]!.number, more.hasMore]).toEqual([p2.number, true, p1.number, false])
    // Liste mit Suchsyntax + Fehlern.
    const list = listIssues(env.ctx, { sort: 'top', page: 1, per: 20, filter: 'status:erledigt,abgelehnt sort:new status:quatsch' }, null)
    expect(list.issues.map((i) => i.number)).toEqual([x.number, d.number])
    expect(list.errors).toEqual(['status:quatsch'])
  })

  it('parses the filter syntax without regexes from the input', () => {
    const q = parseIssueQuery('Autor:@Alex status:"in arbeit",geplant typ:fehler bereich:webseite priorität:hoch,keine zuständig:ich tag:UI ist:alle sort:neu crash (x+ [ .* "zwei wörter"')
    expect(q).toMatchObject({
      author: ['Alex'], status: ['in_progress', 'planned'], type: ['bug'], area: ['website'], priority: ['high', 'none'],
      assignee: ['me'], tag: ['ui'], is: 'all', sort: 'new', text: 'crash (x+ [ .* zwei wörter', errors: [],
    })
    expect(parseIssueQuery('status:foo author:a@b area:mars').errors).toEqual(['status:foo', 'author:a@b', 'area:mars'])
    expect(formatIssueQuery(q, 'de')).toBe('status:in-arbeit,geplant typ:bug bereich:website prio:hoch,keine autor:Alex zustaendig:ich tag:ui ist:alle sort:neu "crash (x+ [ .* zwei wörter"')
    expect(formatIssueQuery(parseIssueQuery(formatIssueQuery(q, 'en'))).length).toBeGreaterThan(10)
    expect(parseIssueQuery(formatIssueQuery(q, 'en'))).toMatchObject({ status: q.status, author: q.author, is: 'all' })
    expect(parseIssueQuery('x'.repeat(1000)).text.length).toBeLessThanOrEqual(80)
  })
})

describe('issues: comments, follows, events', () => {
  it('notifies followers about team answers, status changes and "fixed in" – never the actor', async () => {
    const { env, alex, bea, carl, sup } = await setup()
    const i = newIssue(env, alex)
    setFollow(env.ctx, viewer(env, bea), i.number, true)
    const la = listen(env, alex.uuid)
    const lb = listen(env, bea.uuid)
    const ls = listen(env, sup.uuid)
    addComment(env.ctx, viewer(env, carl), i.number, { body: 'Me too' })
    expect(la.of('issue_updated')).toHaveLength(0)
    addComment(env.ctx, viewer(env, sup), i.number, { body: 'Thanks, we **look** into it. ```code```' })
    expect(la.of('issue_updated')[0]).toMatchObject({ change: 'team_comment', by: { uuid: sup.uuid }, excerpt: 'Thanks, we look into it.' })
    expect(lb.of('issue_updated')).toHaveLength(1)
    expect(ls.of('issue_updated')).toHaveLength(0)
    // Kommentieren = folgen: Carl bekommt jetzt auch Ereignisse.
    const lc = listen(env, carl.uuid)
    adminUpdateIssue(env.ctx, staffOf(env, sup), i.number, { status: 'in_progress' })
    expect(lc.of('issue_updated')[0]).toMatchObject({ change: 'status', status: 'in_progress', issue: { number: i.number, url: i.url } })
    la.clear()
    adminUpdateIssue(env.ctx, staffOf(env, sup), i.number, { status: 'done', fixedIn: '0.13.0' })
    expect(la.of('issue_updated')).toHaveLength(1)
    expect(la.of('issue_updated')[0]).toMatchObject({ change: 'fixed', status: 'done', fixedIn: '0.13.0' })
    setFollow(env.ctx, viewer(env, bea), i.number, false)
    lb.clear()
    adminUpdateIssue(env.ctx, staffOf(env, sup), i.number, { fixedIn: '0.13.1' })
    expect(lb.of('issue_updated')).toHaveLength(0)
    expect(la.of('issue_updated').at(-1)).toMatchObject({ change: 'fixed', fixedIn: '0.13.1' })
    expect(myIssues(env.ctx, viewer(env, carl)).following.map((x) => x.number)).toEqual([i.number])
    expect(myIssues(env.ctx, viewer(env, alex)).created.map((x) => x.number)).toEqual([i.number])
  })

  it('edits and deletes own comments, the team removes others, placeholders stay', async () => {
    const { env, alex, bea, mod } = await setup()
    const i = newIssue(env, alex)
    const c1 = addComment(env.ctx, viewer(env, bea), i.number, { body: 'first' })
    const c2 = addComment(env.ctx, viewer(env, bea), i.number, { body: 'second' })
    expect(editComment(env.ctx, viewer(env, bea), i.number, c1.id, 'first (edited)').editedAt).not.toBeNull()
    expect(code(() => editComment(env.ctx, viewer(env, alex), i.number, c1.id, 'nope'))).toBe('comment_not_found')
    deleteOwnComment(env.ctx, viewer(env, bea), i.number, c1.id)
    adminDeleteComment(env.ctx, staffOf(env, mod), i.number, c2.id)
    const page = issuePage(env.ctx, i.number, null)
    expect(page.comments.map((c) => [c.deleted, c.deletedBy, c.body])).toEqual([[true, 'author', null], [true, 'team', null]])
    expect(page.issue.comments).toBe(0)
  })

  it('locks comments for players but not for the team', async () => {
    const { env, alex, bea, mod, sup } = await setup()
    const i = newIssue(env, alex)
    // Moderator darf nur sperren, nicht sortieren.
    expect(code(() => adminUpdateIssue(env.ctx, staffOf(env, mod), i.number, { status: 'planned' }))).toBe('missing_permission')
    adminUpdateIssue(env.ctx, staffOf(env, mod), i.number, { locked: true })
    expect(code(() => addComment(env.ctx, viewer(env, bea), i.number, { body: 'hello' }))).toBe('issue_locked')
    expect(issuePage(env.ctx, i.number, viewer(env, bea)).issue.can.comment).toBe(false)
    expect(addComment(env.ctx, viewer(env, sup), i.number, { body: 'Team note' }).team).toBe(true)
  })
})

describe('issues: team', () => {
  it('sets priority, assignee, tags, notes and writes the public history', async () => {
    const { env, alex, bea, sup } = await setup()
    const i = newIssue(env, alex)
    const s = staffOf(env, sup)
    expect(code(() => adminUpdateIssue(env.ctx, s, i.number, { assignee: bea.uuid }))).toBe('invalid_assignee')
    expect(code(() => adminUpdateIssue(env.ctx, s, i.number, { status: 'duplicate' }))).toBe('use_merge')
    expect(code(() => adminUpdateIssue(env.ctx, s, i.number, { tags: ['Not Valid!'] }))).toBe('invalid_request')
    const r = adminUpdateIssue(env.ctx, s, i.number, { priority: 'high', assignee: sup.uuid, tags: ['ui', 'crash', 'ui'], area: 'client' })
    expect(r.issue).toMatchObject({ priority: 'high', assignee: { uuid: sup.uuid, name: 'Sup' }, tags: ['crash', 'ui'], area: 'client' })
    expect(r.history.map((h) => h.action)).toEqual(['area', 'priority', 'assignee', 'tags'])
    expect(r.history.find((h) => h.action === 'assignee')).toMatchObject({ from: null, to: 'Sup' })
    const notes = addNote(env.ctx, s, i.number, 'Reproduced on Windows 11')
    expect(notes[0]).toMatchObject({ text: 'Reproduced on Windows 11', author: { name: 'Sup' } })
    expect(issuePage(env.ctx, i.number, viewer(env, bea)).issue.notes).toBeUndefined()
    expect(issuePage(env.ctx, i.number, viewer(env, sup)).issue.notes).toHaveLength(1)
    expect(one(env.ctx.db, "SELECT COUNT(*) AS n FROM admin_log WHERE action LIKE 'issue.%'")).toEqual({ n: 2 })
  })

  it('merges duplicates: votes and followers move, the old issue points to the new one', async () => {
    const { env, alex, bea, carl, sup } = await setup()
    const dup = newIssue(env, alex, { title: 'Crash on start (dup)' })
    const main = newIssue(env, bea, { title: 'Crash on start (main)' })
    voteIssue(env.ctx, viewer(env, alex), dup.number, 1)
    voteIssue(env.ctx, viewer(env, carl), dup.number, 1)
    voteIssue(env.ctx, viewer(env, carl), main.number, -1)
    setFollow(env.ctx, viewer(env, carl), dup.number, true)
    const la = listen(env, alex.uuid)
    const merged = mergeIssue(env.ctx, staffOf(env, sup), dup.number, main.number)
    // Carl hatte am Ziel schon abgestimmt → seine Stimme dort bleibt.
    expect(merged).toMatchObject({ number: main.number, up: 1, down: 1, score: 0 })
    const old = issuePage(env.ctx, dup.number, null).issue
    expect(old).toMatchObject({ status: 'duplicate', locked: true, score: 0, duplicateOf: { number: main.number, status: 'open' } })
    expect(old.closedAt).not.toBeNull()
    expect(la.of('issue_updated')[0]).toMatchObject({ change: 'merged', mergedInto: { number: main.number } })
    const followers = myIssues(env.ctx, viewer(env, carl)).following.map((x) => x.number)
    expect(followers).toEqual([main.number])
    expect(issuePage(env.ctx, main.number, null).history.map((h) => h.action)).toContain('merged_from')
    expect(code(() => mergeIssue(env.ctx, staffOf(env, sup), dup.number, main.number))).toBe('merge_invalid')
    expect(code(() => mergeIssue(env.ctx, staffOf(env, sup), main.number, dup.number))).toBe('merge_invalid')
    expect(code(() => mergeIssue(env.ctx, staffOf(env, sup), main.number, main.number))).toBe('merge_invalid')
  })

  it('deletes and restores issues, deleted ones are gone for players and purged after 90 days', async () => {
    const { env, alex, mod } = await setup()
    const i = newIssue(env, alex)
    adminDeleteIssue(env.ctx, staffOf(env, mod), i.number, 'spam')
    expect(code(() => issuePage(env.ctx, i.number, viewer(env, alex)))).toBe('issue_not_found')
    expect(issuePage(env.ctx, i.number, viewer(env, mod)).issue.deleted).toBe(true)
    expect(listIssues(env.ctx, q(), null).total).toBe(0)
    expect(listIssues(env.ctx, q({ view: 'deleted' }), viewer(env, mod)).issues[0]!.deleted).toBe(true)
    adminRestoreIssue(env.ctx, staffOf(env, mod), i.number)
    expect(listIssues(env.ctx, q(), null).total).toBe(1)
    adminDeleteIssue(env.ctx, staffOf(env, mod), i.number, null)
    env.clock.advance(91 * DAY)
    sweepIssues(env.ctx)
    expect(one(env.ctx.db, 'SELECT 1 AS x FROM issues WHERE id = ?', i.number)).toBeUndefined()
  })

  it('migration 17 adds the permissions to default roles and keeps customised ones', async () => {
    const env = makeEnv()
    updateRole(env.ctx, OWNER, 'moderator', { permissions: ['reports.view'] })
    migrateIssues(env.ctx.db)
    const perms = (id: string) => JSON.parse(one<{ permissions: string }>(env.ctx.db, 'SELECT permissions FROM team_roles WHERE id = ?', id)!.permissions) as string[]
    expect(perms('moderator')).toEqual(['reports.view', 'issues.moderate'])
    expect(perms('supporter')).toContain('issues.manage')
    expect(perms('admin')).toEqual(expect.arrayContaining(['issues.manage', 'issues.moderate']))
    const db = new DatabaseSync(':memory:')
    db.exec('PRAGMA foreign_keys = ON')
    migrate(db)
    migrateIssues(db)
    migrateIssues(db)
    expect(one(db, "SELECT 1 AS x FROM sqlite_master WHERE name = 'chat_reports_issue'")).toBeDefined()
  })
})

describe('issues: uploads, limits, sanctions', () => {
  it('re-encodes uploads, attaches them once and only to the uploader, and hides loose ones from others', async () => {
    const { env, alex, bea } = await setup()
    const up = await uploadIssueImage(env.ctx, alex.uuid, solidPng(64, 32), 'image/png')
    expect(up).toMatchObject({ width: 64, height: 32 })
    expect(up.id).toMatch(/^[A-Za-z0-9_-]{22}$/)
    expect(code(() => readUpload(env.ctx, up.id, viewer(env, bea), false))).toBe('upload_not_found')
    expect(readUpload(env.ctx, up.id, viewer(env, alex), true).public).toBe(false)
    expect(code(() => newIssue(env, bea, { attachments: [up.id] }))).toBe('upload_not_found')
    const i = newIssue(env, alex, { attachments: [up.id] })
    expect(i.attachments.map((a) => a.id)).toEqual([up.id])
    expect(readUpload(env.ctx, up.id, null, false).public).toBe(true)
    expect(code(() => newIssue(env, alex, { attachments: [up.id] }))).toBe('upload_not_found')
    // Nicht angehängt → nach einer Stunde weg (Zeile + Datei).
    const loose = await uploadIssueImage(env.ctx, alex.uuid, solidPng(8, 8), 'image/png')
    env.clock.advance(61 * 60_000)
    expect(code(() => newIssue(env, alex, { attachments: [loose.id] }))).toBe('upload_not_found')
    sweepIssues(env.ctx)
    expect(code(() => readUpload(env.ctx, loose.id, viewer(env, alex), false))).toBe('upload_not_found')
    expect(await codeAsync(() => uploadIssueImage(env.ctx, alex.uuid, Buffer.from('not an image'), 'image/png'))).toBe('unsupported_media_type')
    expect(await codeAsync(() => uploadIssueImage(env.ctx, alex.uuid, solidPng(8, 8), 'image/jpeg'))).toBe('unsupported_media_type')
  })

  it('enforces daily limits', async () => {
    const { env, alex } = await setup()
    for (let n = 0; n < 10; n++) newIssue(env, alex, { title: `Issue number ${n}` })
    expect(code(() => newIssue(env, alex))).toBe('issue_daily_limit')
    env.clock.advance(DAY + 1000)
    expect(newIssue(env, alex).number).toBe(11)
  })

  it('social ban blocks issues and comments, upload ban blocks images, voting stays', async () => {
    const { env, alex, bea } = await setup()
    const i = newIssue(env, alex)
    createSanction(env.ctx, OWNER, { uuid: bea.uuid, kind: 'social_ban', minutes: 60, reasonCode: 'spam', reason: null, note: null })
    createSanction(env.ctx, OWNER, { uuid: bea.uuid, kind: 'upload_ban', minutes: 60, reasonCode: 'spam', reason: null, note: null })
    expect(code(() => newIssue(env, bea))).toBe('sanctioned')
    expect(code(() => addComment(env.ctx, viewer(env, bea), i.number, { body: 'hi' }))).toBe('sanctioned')
    expect(await codeAsync(() => uploadIssueImage(env.ctx, bea.uuid, solidPng(8, 8), 'image/png'))).toBe('sanctioned')
    expect(voteIssue(env.ctx, viewer(env, bea), i.number, 1).score).toBe(1)
  })
})

describe('issues: reports and account deletion', () => {
  it('reports issues and comments, delete_issue needs issues.moderate', async () => {
    const { env, alex, bea, mod, sup } = await setup()
    const i = newIssue(env, alex, { title: 'Rude title here' })
    const c = addComment(env.ctx, viewer(env, alex), i.number, { body: 'rude comment' })
    expect(code(() => createReport(env.ctx, alex.uuid, { kind: 'issue', reason: 'spam', issueNumber: i.number }))).toBe('cannot_target_self')
    const r1 = createReport(env.ctx, bea.uuid, { kind: 'issue_comment', reason: 'insult_hate', commentId: c.id })
    const r2 = createReport(env.ctx, bea.uuid, { kind: 'issue', reason: 'spam', issueNumber: i.number })
    expect(code(() => createReport(env.ctx, bea.uuid, { kind: 'issue', reason: 'spam', issueNumber: i.number }))).toBe('already_reported')
    const d = adminReportDetail(env.ctx, r1.id)
    expect(d).toMatchObject({ kind: 'issue_comment', issueNumber: i.number, issueCommentId: c.id, target: { uuid: alex.uuid }, preview: 'rude comment' })
    expect(d.evidence?.issueComment).toMatchObject({ body: 'rude comment', issueNumber: i.number })
    // Supporter hat reports.handle nicht → Moderator nutzt die Aktion.
    setMemberRoles(env.ctx, OWNER, sup.uuid, ['supporter'])
    adminReportAction(env.ctx, staffOf(env, mod), r1.id, { action: 'delete_issue' })
    expect(issuePage(env.ctx, i.number, null).comments[0]!.deleted).toBe(true)
    // Ohne issues.moderate geht die Aktion nicht.
    updateRole(env.ctx, OWNER, 'moderator', { permissions: ['reports.view', 'reports.handle'] })
    expect(code(() => adminReportAction(env.ctx, staffOf(env, mod), r2.id, { action: 'delete_issue' }))).toBe('missing_permission')
    adminReportAction(env.ctx, OWNER, r2.id, { action: 'delete_issue' })
    expect(code(() => issuePage(env.ctx, i.number, null))).toBe('issue_not_found')
  })

  it('account deletion keeps issues without author, removes votes, logs and uploads', async () => {
    const { env, alex, bea } = await setup()
    const up = await uploadIssueImage(env.ctx, alex.uuid, solidPng(16, 16), 'image/png')
    const i = newIssue(env, alex, { attachments: [up.id], meta: { modVersion: '0.13.0', log: 'something broke' } })
    const other = newIssue(env, bea)
    voteIssue(env.ctx, viewer(env, alex), other.number, 1)
    addComment(env.ctx, viewer(env, alex), other.number, { body: 'my comment' })
    const files = () => (existsSync(env.ctx.issueDir) ? readdirSync(env.ctx.issueDir).flatMap((d) => readdirSync(join(env.ctx.issueDir, d))) : [])
    expect(files()).toHaveLength(2)
    deleteUser(env.ctx, alex.uuid)
    const page = issuePage(env.ctx, i.number, null)
    expect(page.issue.author).toBeNull()
    expect(page.issue.attachments).toEqual([])
    expect(page.issue.meta?.hasLog).toBe(false)
    const o = issuePage(env.ctx, other.number, null)
    expect(o.issue.score).toBe(0)
    expect(o.comments[0]).toMatchObject({ author: null, body: 'my comment' })
    expect(files()).toHaveLength(0)
  })
})
