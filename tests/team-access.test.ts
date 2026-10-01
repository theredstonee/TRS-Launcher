import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { setLocale } from '../app/utils/i18n'
import { liveEventSchema } from '../app/utils/chat'
import { adminReportEnvelopeSchema } from '../app/utils/moderation'
import { adminSanctionSchema } from '../app/utils/team'
import { trsMeSchema } from '../app/utils/trs'
import {
  applicationToast,
  isOpenApplication,
  jobTitle,
  myApplicationSchema,
  myApplicationsSchema,
} from '../app/utils/applications'
import {
  canSearch,
  createdRankOf,
  landingPath,
  mayOpen,
  myTeamSchema,
  permissions,
  roleLabel,
  sanctionRights,
  teamCan,
  teamErrorText,
  visibleSections,
  websiteSections,
  type MyTeam,
} from '../app/utils/teamAccess'

// Team-Rechte (API §24.2): Sichtbarkeit von Bereichen und Knöpfen nach
// `me.team.permissions`, Rang-Regel über `createdRank`, `contentHidden`,
// neue Fehlercodes – und Bewerbungen (§24.3) mit dem Hinweis `application_updated`.

const ME = '75c1a6f3112240abbdb57b9d21c64232'
const OTHER = 'b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0'

// Standardrollen wie in der API (shared/team.ts).
const MOD_BASE = [
  'dashboard.view', 'audit.view', 'reports.view', 'reports.content', 'reports.handle',
  'sanctions.warn', 'sanctions.mute', 'sanctions.social', 'sanctions.upload', 'sanctions.hosting', 'sanctions.lift',
  'appeals.handle', 'players.view', 'players.notes', 'uploads.review', 'worlds.view', 'worlds.close',
]
const team = (rank: number, perms: string[], limits: object = {}, id = 'x'): MyTeam =>
  myTeamSchema.parse({ rank, roles: [{ id, name: null, color: '#22c55e', builtin: true }], permissions: perms, limits })
const admin = team(900, [...permissions], { kinds: ['warn', 'chat_mute', 'social_ban', 'upload_ban', 'hosting_ban', 'account_ban'], maxMinutes: null, maxWarnMinutes: null, permanent: true }, 'admin')
const moderator = team(500, MOD_BASE, { kinds: ['warn', 'chat_mute', 'social_ban', 'upload_ban', 'hosting_ban'], maxMinutes: 10_080, maxWarnMinutes: 43_200 }, 'moderator')
const supporter = team(300, ['dashboard.view', 'reports.view', 'players.view', 'sanctions.warn', 'worlds.view'], { kinds: ['warn'], maxMinutes: 1440, maxWarnMinutes: 43_200 }, 'supporter')
const recruiter = team(150, ['dashboard.view', 'applications.view', 'applications.review'], { kinds: [], maxMinutes: 0, maxWarnMinutes: 0 }, 'recruiter')
const owner = myTeamSchema.parse({ owner: true, rank: 1000, roles: [{ id: 'owner', color: '#facc15', builtin: true }], permissions: [], limits: {} })

const paths = (t: MyTeam | null) => visibleSections(t).map((s) => s.to)
const site = (t: MyTeam | null) => visibleSections(t, websiteSections).map((s) => s.to.split('/').pop())

describe('me.team aus dem Kern', () => {
  it('wird gesäubert: nur bekannte Rechte und Arten, Farben, Rollen', () => {
    const t = myTeamSchema.parse({
      rank: 300,
      roles: [{ id: 'supporter', color: 'rot' }, { id: 'Bad Id!' }, { id: 'event', name: 'Event-Team', color: '#AABBCC' }],
      permissions: ['reports.view', 'nuke.all', 'reports.view', 7],
      limits: { kinds: ['warn', 'nuke'], maxMinutes: 1440 },
    })
    expect(t.permissions).toEqual([])
    const ok = myTeamSchema.parse({ rank: 300, roles: [{ id: 'supporter', color: 'rot' }, { id: 'Bad Id!' }], permissions: ['reports.view', 'nuke.all', 'reports.view'], limits: { kinds: ['warn', 'nuke'] } })
    expect(ok.permissions).toEqual(['reports.view'])
    expect(ok.roles.map((r) => r.id)).toEqual(['supporter'])
    expect(ok.roles[0]!.color).toBe('#9ca3af')
    expect(ok.limits.kinds).toEqual(['warn'])
  })

  it('fehlt bei Nicht-Mitgliedern und bei älteren Kernen', () => {
    const base = { uuid: ME, name: 'Theredstonee', admin: false, createdAt: null, activeCapeId: null,
      settings: { showBadge: true, showCapeToOthers: true, presenceVisibility: 'friends', shareServer: false } }
    expect(trsMeSchema.parse(base).team).toBeNull()
    expect(trsMeSchema.parse({ ...base, team: { rank: 'hoch' } }).team?.rank ?? 0).toBe(0)
    expect(trsMeSchema.parse({ ...base, role: 'moderator', team: { rank: 500, permissions: ['reports.view'], limits: {} } }).team?.permissions).toEqual(['reports.view'])
  })
})

describe('Sichtbarkeit nach Rechten', () => {
  it('Admin sieht alles, Rollen/Bewerbungen/Stellen als Website-Links', () => {
    expect(paths(admin)).toEqual(['/admin', '/admin/reports', '/admin/appeals', '/admin/players', '/admin/sanctions', '/admin/uploads', '/admin/worlds', '/admin/codes', '/admin/word-filter', '/admin/events', '/admin/audit'])
    expect(site(admin)).toEqual(['applications', 'jobs', 'roles', 'blog'])
    expect(websiteSections.every((s) => s.external && s.to.startsWith('https://trs-launcher.theredstonee.de/admin/'))).toBe(true)
  })

  it('Supporter: Übersicht, Meldungen, Spieler, Strafen, Welten – keine Codes, kein Audit, keine Rollen', () => {
    expect(paths(supporter)).toEqual(['/admin', '/admin/reports', '/admin/players', '/admin/sanctions', '/admin/worlds'])
    expect(site(supporter)).toEqual([])
    expect(mayOpen(supporter, '/admin/codes')).toBe(false)
    expect(mayOpen(supporter, '/admin/players/b0b0')).toBe(true)
    expect(mayOpen(supporter, '/admin/roles')).toBe(false)
    expect(teamCan(supporter, 'reports.content')).toBe(false)
    expect(teamCan(supporter, 'reports.handle')).toBe(false)
    expect(canSearch(supporter)).toBe(true)
  })

  it('Blog-Rechte werden erkannt und führen zum Editor auf der Website', () => {
    const content = team(200, ['dashboard.view', 'uploads.review', 'codes', 'blog.write', 'circuits.manage'], {}, 'content')
    expect(content.permissions).toEqual(['dashboard.view', 'uploads.review', 'codes', 'blog.write'])
    expect(site(content)).toEqual(['blog'])
    expect(teamCan(content, 'blog.publish')).toBe(false)
    expect(permissions).toEqual(expect.arrayContaining(['blog.write', 'blog.publish']))
  })

  it('Bewerbungs-Team: nur Übersicht im Launcher, Bewerbungen und Stellen auf der Website', () => {
    expect(paths(recruiter)).toEqual(['/admin'])
    expect(site(recruiter)).toEqual(['applications', 'jobs'])
    expect(canSearch(recruiter)).toBe(false)
    expect(mayOpen(recruiter, '/admin/reports')).toBe(false)
  })

  it('ohne Übersicht landet /admin im ersten erlaubten Bereich; ohne Team nichts', () => {
    const content = team(200, ['uploads.review', 'codes'])
    expect(mayOpen(content, '/admin')).toBe(false)
    expect(landingPath(content)).toBe('/admin/uploads')
    expect(landingPath(null)).toBeNull()
    expect(paths(null)).toEqual([])
    expect(mayOpen(admin, '/admin/unbekannt')).toBe(false)
    // Owner dürfen alles, auch ohne ausdrückliche Rechte in der Liste.
    expect(paths(owner)).toHaveLength(11)
  })
})

describe('Rang-Regel beim Ändern von Strafen', () => {
  const sanction = (extra: object = {}) =>
    adminSanctionSchema.parse({
      id: 7, player: { uuid: OTHER, name: 'Bob' }, kind: 'chat_mute', reasonCode: 'spam', createdAt: 'x',
      createdBy: { uuid: 'c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0', name: 'Mod' }, createdRole: 'moderator', createdRank: 500, status: 'active',
      ...extra,
    })

  it('höherer Rang: gesperrt; gleicher Rang und eigene Strafen: erlaubt', () => {
    expect(sanctionRights(sanction(), moderator, ME)).toEqual({ lift: true, change: true, blocked: null })
    expect(sanctionRights(sanction({ createdRank: 700 }), moderator, ME)).toEqual({ lift: false, change: false, blocked: 'rank' })
    expect(sanctionRights(sanction({ createdRank: 700, createdBy: { uuid: ME, name: 'Ich' } }), moderator, ME).lift).toBe(true)
    expect(sanctionRights(sanction({ createdRank: 900 }), admin, ME).lift).toBe(true)
    expect(sanctionRights(sanction({ createdRank: 1000 }), owner, ME).lift).toBe(true)
  })

  it('ohne `createdRank` (ältere API) zählt die alte Rolle', () => {
    expect(createdRankOf(sanction({ createdRank: undefined, createdRole: 'admin' }))).toBe(900)
    expect(createdRankOf(sanction({ createdRank: null, createdRole: 'system' }))).toBe(0)
    expect(sanctionRights(sanction({ createdRank: null, createdRole: 'admin' }), moderator, ME).blocked).toBe('rank')
  })

  it('Rechte: Supporter darf Stummschaltung nicht ändern, Konto-Bann braucht `sanctions.ban`', () => {
    expect(sanctionRights(sanction({ createdRank: 0 }), supporter, ME)).toEqual({ lift: false, change: false, blocked: 'permission' })
    expect(sanctionRights(sanction({ kind: 'warn', createdRank: 0 }), supporter, ME)).toEqual({ lift: false, change: true, blocked: null })
    expect(sanctionRights(sanction({ kind: 'account_ban', createdRank: 0 }), moderator, ME).blocked).toBe('permission')
    expect(sanctionRights(sanction(), null, ME).blocked).toBe('permission')
  })
})

describe('Meldungen ohne Recht auf Inhalte, neue Fehlercodes', () => {
  it('`contentHidden` und ausgeblendete Nachrichten kommen an', () => {
    const r = adminReportEnvelopeSchema.parse({
      report: {
        id: 'r0123456789abcdef', kind: 'message', reason: 'spam', status: 'open', createdAt: 'x', preview: null, contentHidden: true,
        evidence: { capturedAt: 'x', messages: [{ id: 'm1', createdAt: 'x', text: null, hidden: true }], images: [] },
      },
    }).report
    expect(r.contentHidden).toBe(true)
    expect(r.evidence?.messages[0]?.hidden).toBe(true)
    expect(adminReportEnvelopeSchema.parse({ report: { id: 'r0123456789abcdef', kind: 'message', reason: 'spam', status: 'open', createdAt: 'x' } }).report.contentHidden).toBe(false)
  })

  it('`missing_permission` nennt das Recht in der Launcher-Sprache', async () => {
    await setLocale('de')
    expect(teamErrorText('missing_permission', { permission: 'reports.content' })).toBe('Deiner Rolle fehlt das Recht „Inhalte von Meldungen sehen“.')
    await setLocale('en')
    expect(teamErrorText('missing_permission', { permission: 'roles.manage' })).toBe('Your role is missing the permission “Manage roles”.')
    expect(teamErrorText('missing_permission', { permission: 'evil' })).toBeNull()
    expect(teamErrorText('rank_too_low', {})).toBeNull()
    expect(roleLabel({ id: 'senior_moderator', name: null })).toBe('Senior moderator')
    expect(roleLabel({ id: 'event', name: 'Event-Team' })).toBe('Event-Team')
    expect(roleLabel({ id: 'mystery', name: null })).toBe('mystery')
  })
})

// --- Bewerbungen -------------------------------------------------------------------------

const app = (status: string, extra: object = {}) => ({
  id: 'a0123456789abcdef', job: { id: 'moderator', title: { en: 'Moderator', de: 'Moderator:in' }, open: true },
  status, response: null, createdAt: '2026-09-27T08:00:00.000Z', updatedAt: '2026-09-27T09:00:00.000Z', decidedAt: null,
  canWithdraw: status === 'review', ...extra,
})

describe('Bewerbungen', () => {
  it('Schema, Titel je Sprache, offene Bewerbungen', () => {
    const a = myApplicationSchema.parse(app('review'))
    expect(jobTitle(a, 'de')).toBe('Moderator:in')
    expect(jobTitle(a, 'fr')).toBe('Moderator')
    expect(jobTitle({ job: { id: 'builder', title: {}, open: false } }, 'de')).toBe('builder')
    expect(isOpenApplication(a)).toBe(true)
    expect(myApplicationsSchema.parse([app('new'), { id: '../x' }, app('rejected')])).toHaveLength(2)
    expect(liveEventSchema.safeParse({ type: 'application_updated', application: app('accepted') }).success).toBe(true)
    expect(liveEventSchema.safeParse({ type: 'application_updated', application: app('hired') }).success).toBe(false)
  })

  it('Text des Hinweises mit Antwort des Teams', async () => {
    await setLocale('de')
    const a = myApplicationSchema.parse(app('rejected', { response: 'Danke, diesmal nicht.' }))
    expect(applicationToast(a)).toEqual({ title: 'Deine Bewerbung: Moderator:in', body: 'Diesmal leider nicht angenommen. – „Danke, diesmal nicht.“' })
    await setLocale('en')
    expect(applicationToast(myApplicationSchema.parse(app('interview'))).body).toBe('Invited to an interview.')
  })
})

// Store: Hinweis über den zentralen Sozial-Weg (Stummschaltung im Spiel greift dort).
const social = { quietHours: vi.fn(async () => false), notifyNative: vi.fn(async () => undefined) }
const applications = { mine: vi.fn(async () => [myApplicationSchema.parse(app('review'))]), withdraw: vi.fn() }
vi.mock('../app/utils/backend', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../app/utils/backend')>()),
  backend: { social, applications },
}))
vi.mock('../app/utils/sound', () => ({ playNotificationSound: vi.fn() }))
const { useSocialToasts } = await import('../app/stores/socialToasts')
const { useApplicationsStore } = await import('../app/stores/applications')

describe('Hinweis `application_updated`', () => {
  beforeAll(() => {
    vi.stubGlobal('useTrsStore', () => ({ enabled: true, status: { account: ME } }))
    vi.stubGlobal('document', { hasFocus: () => true, visibilityState: 'visible', documentElement: {} })
  })
  beforeEach(async () => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    await setLocale('de')
  })

  it('zeigt einen Sozial-Hinweis mit „Anzeigen“, ersetzt ihn je Bewerbung und schweigt bei Wiederholung', async () => {
    const store = useApplicationsStore()
    const toasts = useSocialToasts()
    await store.load()
    store.onLiveEvent({ type: 'application_updated', application: myApplicationSchema.parse(app('interview')) })
    await vi.waitFor(() => expect(toasts.items).toHaveLength(1))
    const toast = toasts.items[0]!
    expect(toast.kind).toBe('application')
    expect(toast.key).toBe('application:a0123456789abcdef')
    expect(toast.title).toBe('Deine Bewerbung: Moderator:in')
    expect(toast.body).toBe('Einladung zum Gespräch.')
    toast.actions[0]!.run()
    expect(store.dialogOpen).toBe(true)
    expect(store.focusId).toBe('a0123456789abcdef')
    expect(store.list[0]!.status).toBe('interview')

    // Dasselbe noch einmal (z. B. eigenes Zurückziehen kam schon an): kein zweiter Hinweis.
    toasts.clear()
    store.onLiveEvent({ type: 'application_updated', application: myApplicationSchema.parse(app('interview')) })
    await Promise.resolve()
    expect(toasts.items).toHaveLength(0)
  })

  it('läuft ein Spiel mit TRS Client, schweigt der Launcher (der Client zeigt es)', async () => {
    const store = useApplicationsStore()
    const toasts = useSocialToasts()
    toasts.setGameClients(['survival'])
    store.onLiveEvent({ type: 'application_updated', application: myApplicationSchema.parse(app('accepted')) })
    await new Promise((r) => setTimeout(r, 10))
    expect(toasts.items).toHaveLength(0)
    expect(store.list[0]!.status).toBe('accepted')
  })
})
