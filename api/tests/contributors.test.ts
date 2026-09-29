import { createServer, type Server } from 'node:http'
import { createApp, createRouter, toNodeListener } from 'h3'
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import { cleanLogins, CONTRIBUTORS_MAX, extractContributors, isGithubLogin, mergeContributors } from '../server/lib/contributors'
import { contributorsByVersion, latestRelease } from '../server/lib/site'
import postRoute from '../server/routes/v1/site/blog/[version].get'

describe('Mitwirkende: Parser', () => {
  it('prüft GitHub-Logins streng', () => {
    for (const ok of ['a', 'theredstonee', 'Some-User', 'x1', 'a'.repeat(39)]) expect(isGithubLogin(ok)).toBe(true)
    for (const bad of ['', '-lead', 'a'.repeat(40), 'na me', 'evil"x', '<script>', 'a/b', 'ü', '@name', 'name.x', 'javascript:alert(1)']) {
      expect(isGithubLogin(bad)).toBe(false)
    }
  })

  it('liest bevorzugt den Kommentar und entfernt Abschnitt + Kommentar aus dem Text', () => {
    const body = [
      '# The Big Update',
      '',
      '- **New thing.** Does stuff.',
      '',
      '## Thanks to / Danke an',
      '',
      '@alice, @bob and @ignored-by-comment',
      '',
      '<!-- contributors: alice, Bob ,carol,bad name,<x>,alice -->',
    ].join('\n')
    const r = extractContributors(body)
    expect(r.contributors).toEqual(['alice', 'Bob', 'carol'])
    expect(r.markdown).toBe('# The Big Update\n\n- **New thing.** Does stuff.')
  })

  it('ohne Kommentar: @name aus dem Danke-Abschnitt (auch als Link), nicht aus dem restlichen Text', () => {
    const body = [
      '## What\'s new',
      '',
      '- Reported by @outside in the text.',
      '',
      '### Danke an',
      '',
      '- [@Alice](https://github.com/Alice)',
      '- @bob-2 (first PR!)',
      '- mail@example.com is no mention',
      '',
      '## Neu in dieser Version',
      '',
      '- Noch mehr.',
    ].join('\n')
    const r = extractContributors(body)
    expect(r.contributors).toEqual(['Alice', 'bob-2'])
    expect(r.markdown).toBe('## What\'s new\n\n- Reported by @outside in the text.\n\n## Neu in dieser Version\n\n- Noch mehr.')
  })

  it('erkennt nur echte Danke-Überschriften (EN/DE/ES), nicht „Thanks for playing“', () => {
    for (const h of ['## Thanks to', '## Danke an', '## Gracias a', '#### Thanks to / Danke an', '## Thanks!', '## Danke:']) {
      expect(extractContributors(`${h}\n@alice`).contributors).toEqual(['alice'])
    }
    const keep = '## Thanks for playing\n\n@alice'
    expect(extractContributors(keep)).toEqual({ contributors: [], markdown: keep })
  })

  it('ignoriert Überschriften in Code-Blöcken und Text ohne Abschnitt bleibt gleich', () => {
    const text = '```md\n## Thanks to\n@alice\n```'
    expect(extractContributors(text)).toEqual({ contributors: [], markdown: text })
  })

  it('entfernt Dubletten (Groß-/Kleinschreibung) und begrenzt die Länge', () => {
    expect(cleanLogins(['Alice', 'alice', '@ALICE', '@bob'])).toEqual(['Alice', 'bob'])
    expect(cleanLogins(Array.from({ length: 150 }, (_, i) => `user${i}`))).toHaveLength(CONTRIBUTORS_MAX)
    expect(mergeContributors(['a', 'b'], ['B', 'c'])).toEqual(['a', 'b', 'c'])
  })
})

const CHANGELOG = `# Changelog

## 0.7.0 – 2026-10-02 – The Thanks Update | Das Danke-Update

### English

- **Contributors.** Names on the website.

#### Thanks to

- @changelog-only

### Deutsch

- **Mitwirkende.** Namen auf der Website.

## 0.6.9 – 2026-10-01

### English

- **Quiet.** No contributors.

### Deutsch

- **Leise.** Keine Mitwirkenden.
`

const asset = (tag: string, name: string) => ({ name, browser_download_url: `https://github.com/theredstonee/TRS-Launcher/releases/download/${tag}/${name}`, size: 1 })
const RELEASES = [
  { tag_name: 'updater', html_url: 'https://github.com/x', published_at: '2026-10-03T00:00:00Z', draft: false, body: '<!-- contributors: channel -->', assets: [] },
  {
    tag_name: 'v0.7.0',
    html_url: 'https://github.com/theredstonee/TRS-Launcher/releases/tag/v0.7.0',
    published_at: '2026-10-02T00:00:00Z',
    draft: false,
    body: '# The Thanks Update\n\n- **Contributors.**\n\n## Thanks to / Danke an\n\n@alice @bob\n\n<!-- contributors: alice,bob,"><img src=x> -->\n',
    assets: [asset('v0.7.0', 'TRS.Launcher_0.7.0_x64-setup.exe')],
  },
  { tag_name: 'v0.8.0', html_url: 'https://github.com/x', published_at: '2026-10-05T00:00:00Z', draft: true, body: '<!-- contributors: draft-user -->', assets: [] },
]

describe('Mitwirkende: Release-Texte von GitHub', () => {
  it('ordnet Mitwirkende den Versionen zu (keine Entwürfe, keine Kanal-Releases)', () => {
    const map = contributorsByVersion(RELEASES)
    expect([...map.entries()]).toEqual([['0.7.0', ['alice', 'bob']]])
  })
})

describe('Mitwirkende: Endpunkt /v1/site/blog/{version}', () => {
  const realFetch = globalThis.fetch
  let server: Server
  let base = ''
  const calls: string[] = []

  beforeAll(async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      if (url.startsWith('https://raw.githubusercontent.com/')) return new Response(CHANGELOG, { status: 200 })
      if (url.startsWith('https://api.github.com/')) {
        calls.push(url)
        return new Response(JSON.stringify(RELEASES), { status: 200 })
      }
      return realFetch(input, init)
    }))
    const app = createApp()
    const router = createRouter()
    router.get('/v1/site/blog/:version', postRoute)
    app.use(router)
    server = createServer(toNodeListener(app))
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
    base = `http://127.0.0.1:${(server.address() as { port: number }).port}`
  })
  afterAll(async () => {
    await new Promise<void>((r) => server.close(() => r()))
    vi.unstubAllGlobals()
  })

  it('liefert contributors (Release + Changelog) und den Text ohne Danke-Abschnitt', async () => {
    const res = await realFetch(`${base}/v1/site/blog/0.7.0`)
    expect(res.status).toBe(200)
    const { post } = (await res.json()) as { post: { contributors: string[], markdown: { en: string, de: string } } }
    expect(post.contributors).toEqual(['alice', 'bob', 'changelog-only'])
    expect(post.markdown.en).toBe('- **Contributors.** Names on the website.')
    expect(post.markdown.en).not.toContain('Thanks')
    expect(JSON.stringify(post)).not.toContain('<img')
  })

  it('Version ohne Mitwirkende → leere Liste; Downloads nutzen dieselbe (zwischengespeicherte) Anfrage', async () => {
    const { post } = (await (await realFetch(`${base}/v1/site/blog/0.6.9`)).json()) as { post: { contributors: string[] } }
    expect(post.contributors).toEqual([])
    const latest = await latestRelease()
    expect(latest?.tag).toBe('v0.7.0')
    expect(calls).toHaveLength(1)
  })
})

describe('Mitwirkende: GitHub nicht erreichbar', () => {
  beforeAll(() => {
    vi.resetModules()
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      if (url.startsWith('https://raw.githubusercontent.com/')) return new Response(CHANGELOG, { status: 200 })
      return new Response('down', { status: 503 })
    }))
  })
  afterAll(() => {
    vi.unstubAllGlobals()
  })

  it('der Beitrag bleibt, Mitwirkende nur aus dem Changelog', async () => {
    const site = await import('../server/lib/site')
    const post = await site.blogPost('0.7.0')
    expect(post?.contributors).toEqual(['changelog-only'])
    expect(post?.markdown.en).toBe('- **Contributors.** Names on the website.')
  })
})
