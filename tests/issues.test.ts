import { describe, expect, it } from 'vitest'
import { liveEventSchema } from '../app/utils/chat'
import { setLocale } from '../app/utils/i18n'
import { issueToastText, issueToastUrl, type IssueUpdatedEvent } from '../app/utils/issues'
import { socialSettingsSchema } from '../app/utils/schemas'
import { decide, defaultSocialPrefs, type Situation } from '../app/utils/socialToasts'

const base = {
  type: 'issue_updated',
  change: 'status',
  issue: { number: 57, title: 'Share modpacks by code', kind: 'feature', area: 'launcher', url: 'https://trs-launcher.theredstonee.de/issues/57' },
  by: { uuid: '0123456789abcdef0123456789abcdef', name: 'Sup' },
  status: 'in_progress',
  fixedIn: null,
  mergedInto: null,
  excerpt: null,
  at: '2026-09-28T10:00:00.000Z',
} as const

const ev = (over: Partial<Record<keyof typeof base, unknown>>) => liveEventSchema.parse({ ...base, ...over }) as IssueUpdatedEvent

const away: Situation = { focused: false, visible: true, fullscreen: false, looking: false, muted: false, clientInGame: false }

describe('issue notifications (API §28.6)', () => {
  it('parses the cleaned core event and rejects foreign links', () => {
    expect(ev({}).issue.number).toBe(57)
    expect(liveEventSchema.safeParse({ ...base, issue: { ...base.issue, url: 'javascript:alert(1)' } }).success).toBe(false)
    expect(liveEventSchema.safeParse({ ...base, status: 'burning' }).success).toBe(false)
    expect(liveEventSchema.safeParse({ ...base, change: 'hacked' }).success).toBe(false)
  })

  it('writes a short text for every change and opens the right issue', async () => {
    await setLocale('en')
    expect(issueToastText(ev({}))).toEqual({ title: '#57 Share modpacks by code', body: 'Status: In progress' })
    expect(issueToastText(ev({ change: 'fixed', status: 'done', fixedIn: '0.13.0' })).body).toBe('Fixed in version 0.13.0')
    expect(issueToastText(ev({ change: 'team_comment', excerpt: 'We look into it.' })).body).toBe('Sup (team): We look into it.')
    const merged = ev({ change: 'merged', status: 'duplicate', mergedInto: { number: 12, title: 'Main', kind: null, area: null, url: 'https://trs-launcher.theredstonee.de/issues/12' } })
    expect(issueToastText(merged).body).toBe('Merged into #12 Main')
    expect(issueToastUrl(merged)).toBe('https://trs-launcher.theredstonee.de/issues/12')
    expect(issueToastUrl(ev({}))).toBe('https://trs-launcher.theredstonee.de/issues/57')
    await setLocale('de')
    expect(issueToastText(ev({ change: 'fixed', status: 'done', fixedIn: '0.13.0' })).body).toBe('Erledigt in Version 0.13.0')
    expect(issueToastText(ev({})).body).toBe('Status: In Arbeit')
    await setLocale('en')
  })

  it('respects the "Issues you follow" setting, do not disturb and the game', () => {
    expect(decide('issue', defaultSocialPrefs, away).toast).toBe(true)
    expect(decide('issue', { ...defaultSocialPrefs, issues: false }, away).toast).toBe(false)
    expect(decide('issue', { ...defaultSocialPrefs, doNotDisturb: true }, away).toast).toBe(false)
    expect(decide('issue', defaultSocialPrefs, { ...away, clientInGame: true }).toast).toBe(false)
    // Älterer Kern ohne das Feld: an.
    expect(socialSettingsSchema.parse({}).issues).toBe(true)
  })
})
