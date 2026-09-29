import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  coAuthorEmails,
  collect,
  compareTags,
  contributorsFrom,
  formatList,
  isExcluded,
  loginFromEmail,
  previousTag,
  prNumbers,
  sortUnique,
} from '../scripts/contributors.mjs'

const compare = JSON.parse(readFileSync(path.join(__dirname, 'fixtures', 'contributors-compare.json'), 'utf8'))

describe('Mitwirkende eines Releases', () => {
  it('erkennt Bots und den Maintainer', () => {
    expect(isExcluded('theredstonee')).toBe(true)
    expect(isExcluded('TheRedstonee')).toBe(true)
    expect(isExcluded('dependabot[bot]')).toBe(true)
    expect(isExcluded('github-actions')).toBe(true)
    expect(isExcluded('some-ci-bot')).toBe(true)
    expect(isExcluded('web-flow')).toBe(true)
    expect(isExcluded('alice')).toBe(false)
    expect(isExcluded('theredstonee', [])).toBe(false)
    // Kein gültiger GitHub-Name → nie in die Liste (auch nicht in HTML-Kommentare oder Markdown).
    expect(isExcluded('-x')).toBe(true)
    expect(isExcluded('a b')).toBe(true)
    expect(isExcluded('x-->')).toBe(true)
    expect(isExcluded(undefined)).toBe(true)
  })

  it('liest GitHub-Namen aus noreply-Adressen und Co-Autoren-Zeilen', () => {
    expect(loginFromEmail('1234+alex-dev@users.noreply.github.com')).toBe('alex-dev')
    expect(loginFromEmail('alex-dev@users.noreply.github.com')).toBe('alex-dev')
    expect(loginFromEmail('noreply@example.com')).toBeNull()
    expect(loginFromEmail('alex@users.noreply.github.com.evil.example')).toBeNull()
    expect(coAuthorEmails('Title\n\nCo-authored-by: A <A@x.org>\nco-authored-by: B <b@y.org>\nnot: C <c@z.org>')).toEqual(['a@x.org', 'b@y.org'])
  })

  it('findet PR-Nummern in Squash- und Merge-Commits', () => {
    expect(prNumbers('Add log search (#12)\n\nbody (#77)')).toEqual([12])
    expect(prNumbers('Merge pull request #14 from zoe/fix')).toEqual([14])
    expect(prNumbers('No number here')).toEqual([])
  })

  it('sammelt Autoren, Co-Autoren und PR-Autoren – sortiert, ohne Duplikate, ohne Bots und Maintainer', () => {
    const pulls = [{ user: { login: 'pr-author', type: 'User' } }, { user: { login: 'dependabot[bot]', type: 'Bot' } }]
    expect(contributorsFrom(compare.commits, { pulls })).toEqual(['alex-dev', 'max-mustermann', 'pr-author', 'Zoe-Builds'])
    // Robin hat nur eine normale Adresse ohne verknüpftes Konto im Bereich → kein Name, kein Eintrag.
    expect(contributorsFrom(compare.commits)).not.toContain('robin')
    expect(contributorsFrom(compare.commits, { exclude: [] })).toContain('theredstonee')
    expect(contributorsFrom([])).toEqual([])
  })

  it('holt Commits und gemergte PRs über die API (seitenweise)', async () => {
    const calls: string[] = []
    const pulls: Record<number, unknown> = {
      12: { merged_at: '2026-09-28T10:00:00Z', user: { login: 'Zoe-Builds', type: 'User' } },
      13: { merged_at: '2026-09-28T10:00:00Z', user: { login: 'dependabot[bot]', type: 'Bot' } },
      14: { merged_at: null, user: { login: 'not-merged', type: 'User' } },
    }
    const fetchJson = async (url: string) => {
      calls.push(url)
      if (url.includes('/compare/')) return compare
      const n = Number(/\/pulls\/(\d+)$/.exec(url)?.[1])
      return pulls[n] ?? null
    }
    const list = await collect({ repo: 'o/r', tag: 'v0.15.0', prev: 'v0.14.0', fetchJson })
    expect(list).toEqual(['alex-dev', 'max-mustermann', 'Zoe-Builds'])
    expect(calls[0]).toBe('/repos/o/r/compare/v0.14.0...v0.15.0?per_page=100&page=1')
    expect(calls.filter((c) => c.includes('/pulls/')).sort()).toEqual(['/repos/o/r/pulls/12', '/repos/o/r/pulls/13', '/repos/o/r/pulls/14', '/repos/o/r/pulls/99'])

    // Erstes Release: alle Commits bis zum Tag, Seite für Seite.
    const pages: string[] = []
    const first = await collect({
      repo: 'o/r',
      tag: 'v0.1.0',
      prev: null,
      fetchJson: async (url: string) => {
        pages.push(url)
        if (!url.includes('/commits?')) return null
        return url.endsWith('page=1') ? Array.from({ length: 100 }, () => compare.commits[2]) : [compare.commits[1]]
      },
    })
    expect(first).toEqual(['alex-dev', 'max-mustermann', 'Zoe-Builds'])
    expect(pages.filter((p) => p.includes('/commits?'))).toHaveLength(2)

    await expect(collect({ repo: 'o/r', tag: 'v1.0.0', prev: 'v0.9.0', fetchJson: async () => null })).rejects.toThrow()
  })

  it('findet das vorherige Release-Tag', () => {
    const tags = ['v0.9.0', 'v0.10.0', 'v0.14.0', 'v0.14.1-beta.1', 'v0.13.0', 'updater', 'client-mod']
    expect(previousTag(tags, 'v0.14.0')).toBe('v0.13.0')
    expect(previousTag(tags, 'v0.15.0')).toBe('v0.14.1-beta.1')
    expect(previousTag(tags, 'v0.10.0')).toBe('v0.9.0')
    expect(previousTag(tags, 'v0.9.0')).toBeNull()
    // Kein Release-Tag (Branch/Commit) → das neueste Release.
    expect(previousTag(tags, 'main')).toBe('v0.14.1-beta.1')
    expect(previousTag([], 'v1.0.0')).toBeNull()
    expect(compareTags('v1.0.0-beta', 'v1.0.0')).toBe(-1)
    expect(compareTags('v0.10.0', 'v0.9.9')).toBe(1)
  })

  it('formatiert die Liste', () => {
    expect(sortUnique(['bob', 'Alice', 'alice', 'carl'])).toEqual(['Alice', 'bob', 'carl'])
    expect(formatList(['a', 'b'])).toBe('a,b')
    expect(formatList(['a', 'b'], 'lines')).toBe('a\nb')
    expect(formatList(['a'], 'json')).toBe('["a"]')
    expect(formatList([])).toBe('')
  })
})
